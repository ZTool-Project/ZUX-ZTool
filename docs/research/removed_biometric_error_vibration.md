# Removed biometric failure vibration hook

The biometric failure vibration switch was removed at the user's request.
The former SystemUI hook intercepted
`com.android.systemui.statusbar.VibratorHelper.performHapticFeedback(View, int)`
and skipped calls whose haptic feedback ID was `10005`, identified by the former
implementation as the face/fingerprint authentication failure vibration.
This records the removed implementation's target; current host behavior was not
reverified as part of the removal.
