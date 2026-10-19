package com.example.hospitallocator;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.telephony.SmsManager;
import android.widget.Toast;

public class SOSHelper {

    public static void sendEmergencySMS(Context context, String phoneNumber, double latitude, double longitude) {
        String message = "EMERGENCY! I need assistance. My current location: " +
                "https://maps.google.com/?q=" + latitude + "," + longitude;

        try {
            SmsManager smsManager = SmsManager.getDefault();
            smsManager.sendTextMessage(phoneNumber, null, message, null, null);
            Toast.makeText(context, "SOS Alert Sent!", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setData(Uri.parse("sms:" + phoneNumber));
            intent.putExtra("sms_body", message);
            context.startActivity(intent);
        }
    }
}