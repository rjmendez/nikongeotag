# Keep all app classes: BLE handshake/identity logic relies on reflection
# (BluetoothDevice.removeBond) and javax.crypto Blowfish ciphers.
-keep class com.dama.blepoc.** { *; }
-keepclassmembers class com.dama.blepoc.** { *; }

# Reflection-based Bluetooth bond removal.
-keepclassmembers class android.bluetooth.BluetoothDevice {
    public boolean removeBond();
}

# javax.crypto (Blowfish) usage.
-keep class javax.crypto.** { *; }
-keep class javax.crypto.spec.** { *; }
-dontwarn javax.crypto.**
