package com.sansen.yeweiyi.utils;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Message;

import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class ImageUtils {
	public static void onLoadImage(final URL bitmapUrl,
			final OnLoadImageListener onLoadImageListener) {
		final Handler handler = new Handler() {
			public void handleMessage(Message msg) {
				onLoadImageListener.OnLoadImage((Bitmap) msg.obj, null);
			}
		};
	new Thread(new Runnable() {

			@Override
			public void run() {
				// TODO Auto-generated method stub
				URL imageUrl = bitmapUrl;
				try {
					HttpURLConnection conn = (HttpURLConnection) imageUrl
							.openConnection();
				
					Bitmap bitmap = null ;
					
					if (conn!=null) {
						InputStream inputStream = conn.getInputStream();
						bitmap = BitmapFactory.decodeStream(inputStream);
						
					}
					Message msg = Message.obtain();
				
					msg.obj = bitmap;
			
					handler.sendMessage(msg);
				} catch (Exception e) {
					Message msg = Message.obtain();
					handler.sendMessage(msg);
					
					//e.printStackTrace();
				}
			}

		}).start();

	}

	public interface OnLoadImageListener {
		public void OnLoadImage(Bitmap bitmap, String bitmapPath);
	}
	public static Bitmap getDiskBitmap(String pathString)
	{  
	    Bitmap bitmap = null;
	    try  
	    {  
	        File file = new File(pathString);
	        if(file.exists())  
	        {  
	            bitmap = BitmapFactory.decodeFile(pathString);
	        }  
	    } catch (Exception e)
	    {  
	        // TODO: handle exception  
	    }  
	      
	      
	    return bitmap;  
	}  
}
