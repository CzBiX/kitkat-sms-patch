package com.czbix.xposed.kitkat_sms_patch;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.provider.Telephony.Sms.Intents;

public class SmsReceiver extends BroadcastReceiver {
    private final ComponentName componentName;

    public SmsReceiver(ComponentName componentName) {
        this.componentName = componentName;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent.getAction().equals(Intents.SMS_RECEIVED_ACTION)) {
            intent.setAction(Intents.SMS_DELIVER_ACTION);
        } else if (intent.getAction().equals(Intents.WAP_PUSH_RECEIVED_ACTION)) {
            intent.setAction(Intents.WAP_PUSH_DELIVER_ACTION);
        }
        intent.setComponent(componentName);

        context.sendBroadcast(intent);
    }

    public ComponentName getComponentName() {
        return componentName;
    }
}
