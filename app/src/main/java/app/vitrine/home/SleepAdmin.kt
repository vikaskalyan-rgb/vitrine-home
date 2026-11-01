package app.vitrine.home

import android.app.admin.DeviceAdminReceiver

/** Device admin with a single policy, force-lock, so the header's power button can put the TV to sleep. */
class SleepAdmin : DeviceAdminReceiver()
