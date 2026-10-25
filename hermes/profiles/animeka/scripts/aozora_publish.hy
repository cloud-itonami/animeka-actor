#!/usr/bin/env hy
;; Profile-local launcher for the shared Kotoba AOZORA publisher.
;; Mirrors aozora_common.hy root resolution: COM_JUNKAWASAKI_ROOT may point
;; at the org directory (older launchd environments) or the repo checkout.
(import os)
(import subprocess)
(import sys)

(setv root-candidate (or (os.environ.get "COM_JUNKAWASAKI_ROOT")
                         (os.environ.get "GFTD_ROOT")
                         (os.path.expanduser "~/github/com-junkawasaki/root")))
(setv root (if (os.path.isdir
                 (os.path.join root-candidate "root" "scripts" "hermes-bots-aozora"))
             (os.path.join root-candidate "root")
             root-candidate))
(setv entrypoint (os.path.join root "scripts" "hermes-bots-aozora" "aozora_publish.hy"))
(setv env (dict os.environ))
(.update env {"HERMES_PROFILE" "animeka"})
;; aozora_common.profile() reads argv[2]; pass flags before the profile name.
;; HERMES_PROFILE pins the profile regardless of argv shape.
(setv result (subprocess.run [sys.executable "-m" "hy" entrypoint
                              #* (cut sys.argv 1 (len sys.argv)) "animeka"]
                             :env env
                             :timeout 900))
(sys.exit result.returncode)
