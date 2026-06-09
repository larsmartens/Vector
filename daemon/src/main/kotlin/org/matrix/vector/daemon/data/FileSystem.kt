package org.matrix.vector.daemon.data

import android.content.res.AssetManager
import android.content.res.Resources
import android.os.Binder
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.RemoteException
import android.os.SELinux
import android.os.SharedMemory
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import hidden.HiddenApiBridge
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import org.lsposed.lspd.models.PreLoadedApk
import org.matrix.vector.daemon.BuildConfig
import org.matrix.vector.daemon.utils.ObfuscationManager

private const val TAG = "VectorFileSystem"
private const val VECTOR_DATA_CONTEXT = "u:object_r:xposed_data:s0"

object FileSystem {
  val basePath: Path = Paths.get("/data/adb/lspd")
  val logDirPath: Path = basePath.resolve("log")
  val oldLogDirPath: Path = basePath.resolve("log.old")
  val modulePath: Path = basePath.resolve("modules")
  val socketPath: Path = basePath.resolve(".cli_sock")
  val daemonApkPath: Path = Paths.get(System.getProperty("java.class.path", ""))
  val managerApkPath: Path = daemonApkPath.parent.resolve("manager.apk")
  val configDirPath: Path = basePath.resolve("config")
  val dbPath: File = configDirPath.resolve("modules_config.db").toFile()

  @Volatile private var preloadDex: SharedMemory? = null

  private val formatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME.withZone(ZoneId.systemDefault())
  private val lockPath: Path = basePath.resolve("lock")
  private var fileLock: FileLock? = null
  private var lockChannel: FileChannel? = null

  init {
    runCatching {
          Files.createDirectories(basePath)
          Files.createDirectories(configDirPath)
          setSelinuxContextRecursive(basePath, VECTOR_DATA_CONTEXT)
          Os.chmod("/data/adb", "711".toInt(8))
          Os.chmod(basePath.toString(), "755".toInt(8))
          Os.chmod(configDirPath.toString(), "777".toInt(8))
          configDirPath.toFile().listFiles { file -> file.name.startsWith(dbPath.name) }?.forEach {
            Os.chmod(it.absolutePath, "666".toInt(8))
          }
        }
        .onFailure { Log.e(TAG, "Failed to initialize directories", it) }
  }

  fun setupCli(): String {
    val cliSource = daemonApkPath.parent.resolve("cli").toFile()
    val cliDest = basePath.resolve("cli").toFile()
    if (cliSource.exists()) {
      runCatching {
            cliSource.copyTo(cliDest, overwrite = true)
            Os.chmod(cliDest.absolutePath, "700".toInt(8))
          }
          .onFailure { Log.e(TAG, "Failed to deploy CLI script", it) }
    }

    val cliSocket: String = socketPath.toString()
    val socketFile = File(cliSocket)
    if (socketFile.exists()) {
      Log.d(TAG, "Existing $cliSocket deleted")
      socketFile.delete()
    }

    return cliSocket
  }

  /** Tries to lock the daemon lockfile. Returns false if another daemon is running. */
  fun tryLock(): Boolean {
    return runCatching {
          val permissions =
              PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))
          lockChannel =
              FileChannel.open(
                  lockPath, setOf(StandardOpenOption.CREATE, StandardOpenOption.WRITE), permissions)
          fileLock = lockChannel?.tryLock()
          fileLock?.isValid == true
        }
        .getOrDefault(false)
  }

  /** Clears all special file attributes (like immutable) on a directory. */
  fun chattr0(path: Path): Boolean {
    return runCatching {
          val fd = Os.open(path.toString(), OsConstants.O_RDONLY, 0)
          // 0x40086602 for 64-bit, 0x40046602 for 32-bit (FS_IOC_SETFLAGS)
          val req = if (Process.is64Bit()) 0x40086602 else 0x40046602
          HiddenApiBridge.Os_ioctlInt(fd, req, 0)
          Os.close(fd)
          true
        }
        .recover { e -> if (e is ErrnoException && e.errno == OsConstants.ENOTSUP) true else false }
        .getOrDefault(false)
  }

  /** Recursively sets SELinux context. Crucial for modules to read their data. */
  fun setSelinuxContextRecursive(path: Path, context: String) {
    runCatching {
          SELinux.setFileContext(path.toString(), context)
          if (path.isDirectory()) {
            Files.list(path).use { stream ->
              stream.forEach { setSelinuxContextRecursive(it, context) }
            }
          }
        }
        .onFailure { Log.e(TAG, "Failed to set SELinux context for $path", it) }
  }

  /**
   * Lazily loads resources from the daemon's APK path via reflection. This allows FakeContext to
   * access strings/drawables without a real application context.
   */
  val resources: Resources by lazy {
    val am = AssetManager::class.java.getDeclaredConstructor().newInstance()
    val addAssetPath =
        AssetManager::class.java.getDeclaredMethod("addAssetPath", String::class.java).apply {
          isAccessible = true
        }
    addAssetPath.invoke(am, daemonApkPath.toString())
    @Suppress("DEPRECATION") Resources(am, null, null)
  }

  /** Loads a single DEX file into SharedMemory, optionally applying obfuscation. */
  private fun readDex(inputStream: InputStream, obfuscate: Boolean): SharedMemory {
    var memory = SharedMemory.create(null, inputStream.available())
    val byteBuffer = memory.mapReadWrite()
    Channels.newChannel(inputStream).read(byteBuffer)
    SharedMemory.unmap(byteBuffer)

    if (obfuscate) {
      val newMemory = ObfuscationManager.obfuscateDex(memory)
      if (memory !== newMemory) {
        memory.close()
        memory = newMemory
      }
    }
    memory.setProtect(OsConstants.PROT_READ)
    return memory
  }

  /** Parses the module APK, extracts init lists, and loads DEXes into SharedMemory. */
  fun loadModule(apkPath: String, obfuscate: Boolean): PreLoadedApk? {
    val file = File(apkPath)
    if (!file.exists()) return null

    val preLoadedApk = PreLoadedApk()
    val preLoadedDexes = mutableListOf<SharedMemory>()
    val moduleClassNames = mutableListOf<String>()
    val moduleLibraryNames = mutableListOf<String>()
    var isLegacy = false

    runCatching {
          ZipFile(file).use { zip ->
            // Parse module.prop to get targetApiVersion
            val props =
                zip.getEntry("META-INF/xposed/module.prop")?.let { entry ->
                  zip.getInputStream(entry).bufferedReader().useLines { lines ->
                    lines
                        .filter { it.contains("=") }
                        .associate {
                          val parts = it.split("=", limit = 2)
                          parts[0].trim() to parts[1].trim()
                        }
                  }
                } ?: emptyMap()

            val targetApi = props["targetApiVersion"]?.toIntOrNull() ?: 0
            val hasLegacyFile = zip.getEntry("assets/xposed_init") != null

            // Determine Loading Strategy based on Priority: API 101+ > Legacy > API 100
            val strategy =
                when {
                  targetApi >= 101 -> "MODERN"
                  hasLegacyFile -> "LEGACY"
                  targetApi == 100 -> "UNSUPPORTED" // API 100 is dropped
                  else -> "NONE"
                }

            // Helper to read the list files
            fun readList(name: String, dest: MutableList<String>) {
              zip.getEntry(name)?.let { entry ->
                zip.getInputStream(entry).bufferedReader().useLines { lines ->
                  lines
                      .map { it.trim() }
                      .filter { it.isNotEmpty() && !it.startsWith("#") }
                      .forEach { dest.add(it) }
                }
              }
            }

            when (strategy) {
              "MODERN" -> {
                isLegacy = false
                readList("META-INF/xposed/java_init.list", moduleClassNames)
                readList("META-INF/xposed/native_init.list", moduleLibraryNames)
              }
              "LEGACY" -> {
                isLegacy = true
                readList("assets/xposed_init", moduleClassNames)
                readList("assets/native_init", moduleLibraryNames)
              }
              "UNSUPPORTED" -> {
                Log.w(TAG, "Module $apkPath uses API 100 which is no longer supported.")
                return null
              }
              else -> return null // No valid init files found
            }

            if (moduleClassNames.isEmpty()) return null

            // Read DEX files
            var secondary = 1
            while (true) {
              val entryName = if (secondary == 1) "classes.dex" else "classes$secondary.dex"
              val dexEntry = zip.getEntry(entryName) ?: break
              zip.getInputStream(dexEntry).use { preLoadedDexes.add(readDex(it, obfuscate)) }
              secondary++
            }
          }
        }
        .onFailure {
          Log.e(TAG, "Failed to load module $apkPath", it)
          return null
        }

    if (preLoadedDexes.isEmpty()) return null

    // Apply obfuscation
    if (obfuscate) {
      val signatures = ObfuscationManager.getSignatures()
      for (i in moduleClassNames.indices) {
        val s = moduleClassNames[i]
        signatures.entries
            .firstOrNull { s.startsWith(it.key) }
            ?.let { moduleClassNames[i] = s.replace(it.key, it.value) }
      }
    }

    preLoadedApk.apply {
      this.preLoadedDexes = preLoadedDexes
      this.moduleClassNames = moduleClassNames
      this.moduleLibraryNames = moduleLibraryNames
      this.legacy = isLegacy
    }

    return preLoadedApk
  }

  /** Safely creates the log directory. If a file exists with the same name, it deletes it first. */
  private fun createLogDirPath() {
    if (!Files.isDirectory(logDirPath, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
      logDirPath.toFile().deleteRecursively()
    }
    Files.createDirectories(logDirPath)
  }

  /**
   * Rotates the log directory by clearing file attributes (chattr 0), deleting the old backup, and
   * renaming the current log directory to the backup.
   */
  fun moveLogDir() {
    runCatching {
          if (Files.exists(logDirPath)) {
            if (chattr0(logDirPath)) {
              // Kotlin's deleteRecursively replaces the verbose Java SimpleFileVisitor
              oldLogDirPath.toFile().deleteRecursively()
              Files.move(logDirPath, oldLogDirPath)
            }
          }
          Files.createDirectories(logDirPath)
        }
        .onFailure { Log.e(TAG, "Failed to move log directory", it) }
  }

  fun getPropsPath(): File {
    createLogDirPath()
    return logDirPath.resolve("props.txt").toFile()
  }

  fun getKmsgPath(): File {
    createLogDirPath()
    return logDirPath.resolve("kmsg.log").toFile()
  }

  @Synchronized
  fun getPreloadDex(obfuscate: Boolean): SharedMemory? {
    if (preloadDex == null) {
      runCatching {
            FileInputStream("framework/lspd.dex").use { preloadDex = readDex(it, obfuscate) }
          }
          .onFailure { Log.e(TAG, "Failed to load framework dex", it) }
    }
    return preloadDex
  }

  fun ensureModuleFilePath(path: String?) {
    if (path == null || path.contains(File.separatorChar) || path == "." || path == "..") {
      throw RemoteException("Invalid path: $path")
    }
  }

  fun resolveModuleDir(packageName: String, dir: String, userId: Int, uid: Int): Path {
    val path = modulePath.resolve(userId.toString()).resolve(packageName).resolve(dir).normalize()
    path.toFile().mkdirs()

    if (SELinux.getFileContext(path.toString()) != "u:object_r:xposed_data:s0") {
      runCatching {
            setSelinuxContextRecursive(path, "u:object_r:xposed_data:s0")
            if (uid != -1) Os.chown(path.toString(), uid, uid)
            Os.chmod(path.toString(), "755".toInt(8))
          }
          .onFailure { throw RemoteException("Failed to set SELinux context: ${it.message}") }
    }
    return path
  }

  fun toGlobalNamespace(path: String): File {
    return if (path.startsWith("/")) File("/proc/1/root", path) else File("/proc/1/root/$path")
  }

  fun getLogs(zipFd: ParcelFileDescriptor) {
    runCatching {
          ZipOutputStream(java.io.FileOutputStream(zipFd.fileDescriptor)).use { os ->
            val comment =
                "Vector ${BuildConfig.BUILD_TYPE} ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
            os.setComment(comment)
            os.setLevel(java.util.zip.Deflater.BEST_COMPRESSION)

            fun addFile(name: String, file: File) {
              if (!file.exists() || !file.isFile) return
              runCatching {
                    os.putNextEntry(ZipEntry(name))
                    file.inputStream().use { it.copyTo(os) }
                    os.closeEntry()
                  }
                  .onFailure { Log.e(TAG, "Failed to export $file as $name", it) }
            }

            fun addDir(basePath: String, dir: File) {
              if (!dir.exists() || !dir.isDirectory) return
              dir.walkTopDown()
                  .filter { it.isFile }
                  .forEach { file ->
                    val relativePath = dir.toPath().relativize(file.toPath()).toString()
                    val entryName =
                        if (basePath.isEmpty()) relativePath else "$basePath/$relativePath"
                    addFile(entryName, file)
                  }
            }

            fun addProcOutput(name: String, vararg cmd: String) {
              runCatching {
                val proc = ProcessBuilder(*cmd).start()
                os.putNextEntry(ZipEntry(name))
                proc.inputStream.use { it.copyTo(os) }
                os.closeEntry()
              }
            }

            // Gather system crash traces
            addDir("tombstones", File("/data/tombstones"))
            addDir("anr", File("/data/anr"))
            addDir(
                "crash_shell",
                File("/data/data/${BuildConfig.MANAGER_INJECTED_PKG_NAME}/cache/crash"))
            addDir(
                "crash_manager",
                File("/data/data/${BuildConfig.DEFAULT_MANAGER_PACKAGE_NAME}/cache/crash"))

            // Gather system logs directly via shell
            addProcOutput("full.log", "logcat", "-b", "all", "-d")
            addProcOutput("dmesg.log", "dmesg")

            // Gather system module states safely
            val magiskDataDir = File("/data/adb/modules")
            if (magiskDataDir.exists() && magiskDataDir.isDirectory) {
              magiskDataDir.listFiles()?.forEach { moduleDir ->
                val modName = moduleDir.name
                listOf("module.prop", "remove", "disable", "update", "sepolicy.rule").forEach {
                  addFile("modules/$modName/$it", File(moduleDir, it))
                }
              }
            }

            // Gather memory/mount info for daemon and caller
            val proc = File("/proc")
            arrayOf("self", Binder.getCallingPid().toString()).forEach { pid ->
              val pidPath = File(proc, pid)
              listOf("maps", "mountinfo", "status").forEach {
                addFile("proc/$pid/$it", File(pidPath, it))
              }
            }

            // Gather Database and Scopes
            addFile("modules_config.db", dbPath)
            runCatching {
                  val scopes = ConfigCache.state.scopes
                  Log.d(TAG, "Exporting scopes for ${scopes.size} targets")
                  os.putNextEntry(ZipEntry("scopes.txt"))
                  scopes.forEach { (scope, modules) ->
                    os.write("${scope.processName}/${scope.uid}\n".toByteArray())
                    modules.forEach { mod ->
                      os.write("\t${mod.packageName}\n".toByteArray())
                      mod.file?.moduleClassNames?.forEach { cn ->
                        os.write("\t\t$cn\n".toByteArray())
                      }
                      mod.file?.moduleLibraryNames?.forEach { ln ->
                        os.write("\t\t$ln\n".toByteArray())
                      }
                    }
                  }
                  os.closeEntry()
                }
                .onFailure { Log.e(TAG, "Failed to export module scopes", it) }

            // Gather daemon logs
            addDir("log", logDirPath.toFile())
            addDir("log.old", oldLogDirPath.toFile())
          }
        }
        .onFailure { Log.e(TAG, "Failed to export logs", it) }
        .also { runCatching { zipFd.close() } }
  }

  private fun getNewLogFileName(prefix: String): String {
    return "${prefix}_${formatter.format(Instant.now())}.log"
  }

  fun getNewVerboseLogPath(): File {
    createLogDirPath()
    return logDirPath.resolve(getNewLogFileName("verbose")).toFile()
  }

  fun getNewModulesLogPath(): File {
    createLogDirPath()
    return logDirPath.resolve(getNewLogFileName("modules")).toFile()
  }
  private fun readActiveReinjectionOwner(): ReinjectionOwner? {
    return withRootFileAccess { withReinjectionMetadataLock { readReinjectionOwnerLocked() } }
  }

  private inline fun <T> withReinjectionMetadataLock(block: () -> T): T {
    preparePrivateFile(reinjectionOwnerLockPath)
    val channel =
        FileChannel.open(
            reinjectionOwnerLockPath,
            setOf(StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE))
    channel.use { lockedChannel ->
      val lock = lockedChannel.lock()
      try {
        return block()
      } finally {
        runCatching { lock.release() }
      }
    }
  }

  private fun ensureActiveReinjectionOwnerLocked(instanceId: String): ReinjectionOwnerState {
    val currentOwner = readReinjectionOwnerLocked()
    val pid = Process.myPid()
    if (currentOwner?.instanceId == instanceId && currentOwner.pid == pid) {
      return ReinjectionOwnerState(true, currentOwner, false)
    }
    if (currentOwner != null && isDaemonOwnerAlive(currentOwner)) {
      return ReinjectionOwnerState(false, currentOwner, false)
    }

    val newOwner = ReinjectionOwner(instanceId, pid, System.currentTimeMillis())
    if (!writeReinjectionOwnerLocked(newOwner)) {
      Log.e(TAG, "Failed to claim reinjection ownership for `${newOwner.toLogString()}`")
      return ReinjectionOwnerState(false, currentOwner, false)
    }
    Log.i(
        TAG,
        "Reinjection owner changed from `${currentOwner?.toLogString() ?: "none"}` to `${newOwner.toLogString()}`")
    return ReinjectionOwnerState(true, newOwner, true)
  }

  private fun readReinjectionOwnerLocked(): ReinjectionOwner? {
    if (!reinjectionOwnerPath.exists()) return null
    return runCatching {
          Files.newBufferedReader(reinjectionOwnerPath).use { reader ->
            parseReinjectionOwner(reader.readLine())
          }
        }
        .onFailure { Log.w(TAG, "Failed to read reinjection owner state", it) }
        .getOrNull()
  }

  private fun writeReinjectionOwnerLocked(owner: ReinjectionOwner): Boolean {
    preparePrivateFile(reinjectionOwnerPath)
    return runCatching {
          Files.newBufferedWriter(
                  reinjectionOwnerPath,
                  StandardOpenOption.CREATE,
                  StandardOpenOption.TRUNCATE_EXISTING,
                  StandardOpenOption.WRITE)
              .use { writer ->
                writer.write(
                    listOf(owner.instanceId, owner.pid.toString(), owner.claimedAtMillis.toString())
                        .joinToString("|"))
              }
          true
        }
        .onFailure { Log.e(TAG, "Failed to persist reinjection owner state", it) }
        .getOrDefault(false)
  }

  private fun parseReinjectionOwner(raw: String?): ReinjectionOwner? {
    val value = raw?.trim().orEmpty()
    if (value.isEmpty()) return null
    val parts = value.split('|')
    if (parts.size != 3) return null
    val pid = parts[1].toIntOrNull() ?: return null
    val claimedAt = parts[2].toLongOrNull() ?: return null
    return ReinjectionOwner(parts[0], pid, claimedAt)
  }

  private fun isDaemonOwnerAlive(owner: ReinjectionOwner): Boolean {
    if (!isPidAlive(owner.pid)) return false
    if (isExpectedDaemonProcess(owner.pid)) return true

    Log.w(TAG, "Ignoring stale reinjection owner `${owner.toLogString()}`: PID was reused")
    return false
  }

  private fun isPidAlive(pid: Int): Boolean {
    if (pid <= 0) return false

    val procVisible = Files.exists(Paths.get("/proc/$pid"))
    return runCatching {
          Os.kill(pid, 0)
          true
        }
        .recover { e ->
          if (e is ErrnoException) {
            when (e.errno) {
              OsConstants.ESRCH -> false
              OsConstants.EPERM -> true
              else -> procVisible
            }
          } else {
            procVisible
          }
        }
        .getOrDefault(procVisible)
  }

  private fun isExpectedDaemonProcess(pid: Int): Boolean {
    val cmdlinePath = Paths.get("/proc/$pid/cmdline")
    return runCatching {
          val cmdline =
              String(Files.readAllBytes(cmdlinePath))
                  .trimEnd('\u0000')
                  .replace('\u0000', ' ')
          cmdline == "lspd" ||
              cmdline.endsWith("/lspd") ||
              cmdline.contains("org.matrix.vector.daemon.VectorDaemon")
        }
        .getOrDefault(false)
  }

  private fun nextReinjectionRoundLocked(): Long {
    preparePrivateFile(reinjectionRoundPath)
    val current =
        runCatching {
              if (!reinjectionRoundPath.exists()) {
                0L
              } else {
                Files.newBufferedReader(reinjectionRoundPath).use { it.readLine().toLongOrNull() ?: 0L }
              }
            }
            .getOrDefault(0L)
    val next = current + 1
    runCatching {
          Files.newBufferedWriter(
                  reinjectionRoundPath,
                  StandardOpenOption.CREATE,
                  StandardOpenOption.TRUNCATE_EXISTING,
                  StandardOpenOption.WRITE)
              .use { it.write(next.toString()) }
        }
        .onFailure { Log.w(TAG, "Failed to persist reinjection round counter", it) }
    return next
  }

  private inline fun <T> withRootFileAccess(block: () -> T): T {
    val originalEuid = runCatching { Os.geteuid() }.getOrDefault(0)
    val switched = originalEuid != 0 && runCatching { Os.seteuid(0) }.isSuccess
    try {
      return block()
    } finally {
      if (switched) {
        runCatching { Os.seteuid(originalEuid) }
            .onFailure { Log.w(TAG, "Failed to restore euid to $originalEuid", it) }
      }
    }
  }

  private fun preparePrivateFile(path: Path) {
    runCatching {
          val parent = path.parent
          if (parent != null && !parent.exists()) {
            Files.createDirectories(parent)
            Os.chmod(parent.toString(), PRIVATE_DIR_MODE)
            SELinux.setFileContext(parent.toString(), SYSTEM_FILE_CONTEXT)
          }

          val file = path.toFile()
          if (!file.exists()) {
            file.createNewFile()
          }

          Os.chmod(path.toString(), PRIVATE_FILE_MODE)
          if (SELinux.getFileContext(path.toString()) != SYSTEM_FILE_CONTEXT) {
            SELinux.setFileContext(path.toString(), SYSTEM_FILE_CONTEXT)
          }
        }
        .onFailure { Log.w(TAG, "Failed to prepare private file $path", it) }
  }
}
