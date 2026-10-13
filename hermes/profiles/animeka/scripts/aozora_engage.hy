#!/usr/bin/env hy
;; Profile-local launcher for the shared Kotoba AOZORA engage entrypoint.
(import os)
(import subprocess)
(import sys)

(setv root (or (os.environ.get "COM_JUNKAWASAKI_ROOT")
               (os.path.expanduser "~/github/com-junkawasaki")))
(setv entrypoint (os.path.join root "scripts" "hermes-bots-aozora" "aozora_engage.hy"))
(setv result (subprocess.run [sys.executable "-m" "hy" entrypoint "animeka" #* (cut sys.argv 1)]
                             :timeout 900))
(sys.exit result.returncode)
