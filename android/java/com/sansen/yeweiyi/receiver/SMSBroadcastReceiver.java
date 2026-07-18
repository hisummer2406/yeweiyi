package com.sansen.yeweiyi.receiver;


import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.telephony.SmsMessage;
import android.widget.Toast;

import com.sansen.yeweiyi.db.SmsStencil;
import com.sansen.yeweiyi.manager.DBDataManager;
import com.sansen.yeweiyi.utils.SystemUtils;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

public class SMSBroadcastReceiver extends BroadcastReceiver {
    public  String [] znLists ={"<罐号>","<油品>","<油高>","<水高>","<存油体积>"
    ,"<出油重量>","<空油量>","<温度>"};
    public void onReceive(Context context, Intent intent) {
        SmsMessage msg = null;
        Bundle bundle = intent.getExtras();
//            System.out.println("Bundle  "+bundle);
        if (bundle != null) {
            Object[] pdusObj = (Object[]) bundle.get("pdus");
            for (Object p : pdusObj) {
                msg = SmsMessage.createFromPdu((byte[]) p);

                String msgTxt = msg.getMessageBody();//得到消息的内容

                Date date = new Date(msg.getTimestampMillis());//时间
                SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                String receiveTime = format.format(date);

                String senderNumber = msg.getOriginatingAddress();

                if (msgTxt.equals("Testing!")) {
                    Toast.makeText(context, "success!", Toast.LENGTH_LONG)
                            .show();
                    System.out.println("信息  success!");
                    return;
                } else {
                    Toast.makeText(context, msgTxt, Toast.LENGTH_LONG).show();
                    System.out.println("信息  发送人：" + senderNumber + "  短信内容：" + msgTxt + "接受时间：" + receiveTime);
                    List<SmsStencil> smsStencils =DBDataManager.getInstance().findAllObject(SmsStencil.class);
                    for (SmsStencil sms :smsStencils) {
                        if (String.valueOf(sms.getId()).equals(msgTxt)){
                            sendSMS(context,sms,senderNumber);
                            break;
                        }
                    }
                    return;
                }
            }
            return;
        }
    }

    private void sendSMS(Context context,SmsStencil sms, String senderNumber) {
        String content=sms.getContent();
        for (int i=0;i<znLists.length;i++){
            content.replace(znLists[i],getValue(znLists[i]));
        }
        SystemUtils.sendMessageBySysterm(context,senderNumber,"");
    }

    private String getValue(String znList) {
        return  null;
    }
}