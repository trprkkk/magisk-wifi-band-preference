#!/system/bin/sh
# No persistent files outside the module directory. Remove a temporary
# runtime copy if an older test version created one.
rm -rf /data/local/tmp/wifi_band_preference
