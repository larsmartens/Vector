MANAGER_PACKAGE_NAME="@MANAGER_PACKAGE_NAME@"
INJECTED_PACKAGE_NAME="@INJECTED_PACKAGE_NAME@"
MODDIR="${0%/*}"

launch_standalone_manager() {
  if [ -f "$MODDIR/manager.apk" ]; then
    install_out="$(pm install -r "$MODDIR/manager.apk" 2>&1)" || {
      case "$install_out" in
        *INSTALL_FAILED_UPDATE_INCOMPATIBLE*)
          pm uninstall "$MANAGER_PACKAGE_NAME" >/dev/null 2>&1 || true
          pm install "$MODDIR/manager.apk" >/dev/null 2>&1 || return 1
          ;;
        *)
          return 1
          ;;
      esac
    }
  fi

  if pm path "$MANAGER_PACKAGE_NAME" >/dev/null 2>&1; then
    am start -n "$MANAGER_PACKAGE_NAME/.ui.activity.MainActivity" >/dev/null 2>&1 ||
      am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p "$MANAGER_PACKAGE_NAME" >/dev/null 2>&1
    return $?
  fi

  return 1
}

if [ "$(getprop ro.build.version.sdk)" -ge 36 ]; then
  launch_standalone_manager
  exit $?
fi

am start -c "${MANAGER_PACKAGE_NAME}.LAUNCH_MANAGER" "${INJECTED_PACKAGE_NAME}/.BugreportWarningActivity"
