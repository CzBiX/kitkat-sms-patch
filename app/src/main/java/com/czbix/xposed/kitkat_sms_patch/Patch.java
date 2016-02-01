package com.czbix.xposed.kitkat_sms_patch;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.UserHandle;
import android.provider.Telephony.Sms.Intents;
import android.telephony.SubscriptionManager;
import android.util.Log;

import com.android.internal.telephony.InboundSmsHandler;
import com.android.internal.telephony.PhoneBase;

import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodHook.MethodHookParam;
import de.robv.android.xposed.XposedHelpers;

public class Patch implements IXposedHookZygoteInit {
    private static final String TAG = Patch.class.getName();

    private static final Class<?> smsBroadcastReceiver = XposedHelpers.findClass(
            "com.android.internal.telephony.InboundSmsHandler$SmsBroadcastReceiver", null);

    private SmsReceiver smsReceiver;
    private SmsReceiver mmsReceiver;

    @Override
    public void initZygote(StartupParam startupParam) throws Throwable {
        if (!Utils.isKkOrLater) {
            Log.e(TAG, "older than KitKat, skip init zygote!");
            return;
        }

        allowAbortSmsBroadcast();
        ignoreDeliverBroadcast();

        Log.i(TAG, "KitKat SMS Patch loaded!");
    }

    private void allowAbortSmsBroadcast() {
        if (Utils.isMmOrLater) {
            XposedHelpers.findAndHookMethod(InboundSmsHandler.class, "dispatchIntent",
                    Intent.class, String.class, int.class, Bundle.class, BroadcastReceiver.class, UserHandle.class,
                    dispatchIntentHook);
        } else if (Utils.isLpOrLater) {
            XposedHelpers.findAndHookMethod(InboundSmsHandler.class, "dispatchIntent",
                    Intent.class, String.class, int.class, BroadcastReceiver.class, UserHandle.class,
                    dispatchIntentHook);
        } else {
            XposedHelpers.findAndHookMethod(InboundSmsHandler.class, "dispatchIntent",
                    Intent.class, String.class, int.class, BroadcastReceiver.class, dispatchIntentHook);
        }
    }

    private void ignoreDeliverBroadcast() {
        XposedHelpers.findAndHookMethod(smsBroadcastReceiver, "onReceive",
                Context.class, Intent.class, smsBroadcastOnReceive);
    }

    private static final XC_MethodHook smsBroadcastOnReceive = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
            Intent intent = (Intent) param.args[1];
            String action = intent.getAction();
            if (action.equals(Intents.SMS_DELIVER_ACTION)
                    || action.equals(Intents.WAP_PUSH_DELIVER_ACTION)) {
                // already send, treat as anther action
                Log.i(TAG, "received deliver action");

                intent.setAction("czbix.sms.deliver.finish");
            }
        }
    };

    private final XC_MethodHook dispatchIntentHook = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
            Intent intent = (Intent) param.args[0];
            final String action = intent.getAction();

            boolean isMms = false;
            switch (action) {
                case Intents.SMS_DELIVER_ACTION:
                    intent.setAction(Intents.SMS_RECEIVED_ACTION);
                    break;
                case Intents.WAP_PUSH_DELIVER_ACTION:
                    isMms = true;
                    intent.setAction(Intents.WAP_PUSH_RECEIVED_ACTION);
                    break;
                default:
                    // not interesting
                    return;
            }

            Context context = (Context) XposedHelpers.getObjectField(param.thisObject, "mContext");
            updateDefaultSmsReceiver(context, intent.getComponent(), isMms);
            intent.setComponent(null);

            Log.d(TAG, "received sms, send broadcast to sms blocker");

            String perm = (String) param.args[1];
            int appOp = ((Integer) param.args[2]);
            BroadcastReceiver receiver = (BroadcastReceiver) param.args[Utils.isMmOrLater ? 4 : 3];

            if (Utils.isMmOrLater) {
                final Bundle opts = (Bundle) param.args[3];
                handleForMm(param, context, intent, perm, appOp, opts, receiver);
            } else if (Utils.isLpOrLater) {
                handleForLp(param, context, intent, perm, appOp, receiver);
            } else {
                callSendBroadcast(param.thisObject, context, intent, perm, appOp, receiver);
            }

            // skip original method
            param.setResult(null);
        }
    };

    private void handleForMm(MethodHookParam param, Context context, Intent intent, String perm,
                             int appOp, Bundle opts, BroadcastReceiver receiver) {
        UserHandle userHandle = (UserHandle) param.args[5];

        putPhoneIdAndSubIdExtra(param.thisObject, intent);
        if (isAllUser(userHandle)) {
            Log.w(TAG, "there is a bug that only broadcast intent to owner user");
            userHandle = (UserHandle) XposedHelpers.getStaticObjectField(UserHandle.class, "OWNER");
        }

        callSendBroadcastAsUser(param.thisObject, context, intent, userHandle, perm, appOp, opts, receiver);
    }

    private void handleForLp(MethodHookParam param, Context context, Intent intent, String perm,
                             int appOp, BroadcastReceiver receiver) {
        UserHandle userHandle = (UserHandle) param.args[4];

        putPhoneIdAndSubIdExtra(param.thisObject, intent);
        if (isAllUser(userHandle)) {
            Log.w(TAG, "there is a bug that only broadcast intent to owner user");
            userHandle = (UserHandle) XposedHelpers.getStaticObjectField(UserHandle.class, "OWNER");
        }

        callSendBroadcastAsUser(param.thisObject, context, intent, userHandle, perm, appOp, receiver);
    }

    private void updateDefaultSmsReceiver(Context context, ComponentName componentName, boolean isMms) {
        SmsReceiver receiver = isMms ? mmsReceiver : smsReceiver;
        if (receiver != null) {
            if (receiver.getComponentName().equals(componentName)) {
                return;
            }
            context.unregisterReceiver(receiver);

            if (isMms) {
                mmsReceiver = null;
            } else {
                smsReceiver = null;
            }
        }

        if (componentName == null) {
            return;
        }

        receiver = new SmsReceiver(componentName);
        context.registerReceiver(receiver, new IntentFilter(isMms
                ? Intents.WAP_PUSH_RECEIVED_ACTION : Intents.SMS_RECEIVED_ACTION));
        if (isMms) {
            mmsReceiver = receiver;
        } else {
            smsReceiver = receiver;
        }
    }

    private static void callSendBroadcastAsUser(Object thisObject, Context context, Intent intent,
                                                UserHandle user, String perm, int appOp, Bundle opts,
                                                BroadcastReceiver receiver) {
        XposedHelpers.callMethod(context, "sendOrderedBroadcastAsUser", intent, user, perm,
                appOp, opts, receiver, XposedHelpers.callMethod(thisObject, "getHandler"), Activity.RESULT_OK, null, null);
    }

    private static void callSendBroadcastAsUser(Object thisObject, Context context, Intent intent,
                                                UserHandle user, String perm, int appOp,
                                                BroadcastReceiver receiver) {
        XposedHelpers.callMethod(context, "sendOrderedBroadcastAsUser", intent, user, perm,
                appOp, receiver, XposedHelpers.callMethod(thisObject, "getHandler"), Activity.RESULT_OK, null, null);
    }

    private static void callSendBroadcast(Object thisObject, Context context, Intent intent, String perm, int appOp, BroadcastReceiver receiver) {
        XposedHelpers.callMethod(context, "sendOrderedBroadcast", intent, perm,
                appOp, receiver, XposedHelpers.callMethod(thisObject, "getHandler"), Activity.RESULT_OK, null, null);
    }

    private static void putPhoneIdAndSubIdExtra(Object thisObject, Intent intent) {
        PhoneBase phone = (PhoneBase) XposedHelpers.getObjectField(thisObject, "mPhone");
        XposedHelpers.callStaticMethod(SubscriptionManager.class, "putPhoneIdAndSubIdExtra",
                intent, phone.getPhoneId());
    }

    private static boolean isAllUser(UserHandle userHandle) {
        final Object allUser = XposedHelpers.getStaticObjectField(UserHandle.class, "ALL");
        return userHandle.equals(allUser);
    }
}
