#!/system/bin/sh

BRIDGE_JAR=$1
MODDIR=$2
CHILD_PID=

stop_child() {
  if [ -n "$CHILD_PID" ]; then
    kill "$CHILD_PID" 2>/dev/null
    wait "$CHILD_PID" 2>/dev/null
  fi
  exit 0
}

trap stop_child TERM INT HUP
echo "SUPERVISOR_READY"

while [ -f "$MODDIR/.guard_ok" ]; do
  if [ "$(settings get global bluetooth_on 2>/dev/null)" != "1" ]; then
    sleep 5
    continue
  fi

  CLASSPATH="$BRIDGE_JAR" app_process /system/bin LhdcControlBridge auto &
  CHILD_PID=$!
  wait "$CHILD_PID"
  RESULT=$?
  CHILD_PID=
  echo "AUTO_EXIT code=$RESULT; restart in 3s"
  sleep 3
done

