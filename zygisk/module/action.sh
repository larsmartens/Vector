MANAGER_PACKAGE_NAME="@MANAGER_PACKAGE_NAME@"
INJECTED_PACKAGE_NAME="@INJECTED_PACKAGE_NAME@"
MODDIR="${0%/*}"

launch_standalone_manager() {
  if [ -f "$MODDIR/manager.apk" ]; then
    pm install -r "$MODDIR/manager.apk" >/dev/null 2>&1 || true
  fi

  if pm path "$MANAGER_PACKAGE_NAME" >/dev/null 2>&1; then
    am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p "$MANAGER_PACKAGE_NAME" >/dev/null 2>&1
    return $?
  fi

  return 1
}

if [ "$(getprop ro.build.version.sdk)" -ge 36 ]; then
  launch_standalone_manager && exit 0
fi

am start -c "${MANAGER_PACKAGE_NAME}.LAUNCH_MANAGER" "${INJECTED_PACKAGE_NAME}/.BugreportWarningActivity"
