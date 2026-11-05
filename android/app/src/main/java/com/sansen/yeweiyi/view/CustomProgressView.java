package com.sansen.yeweiyi.view;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import com.sansen.yeweiyi.fragment.ColorFragment;

public class CustomProgressView extends View {

	private static final String TAG = "CustomProgressView";
	private Paint p;
	int maxheight=10;
	private int oilRatio;
	private int waterRatio;
	private int color;
	private int height;
	private int width;
	private double latticeHeight;//格子高度
	private double spacing;//格子高度
	private int waterColor= new Color().rgb(193, 210, 240);
	@Override
	protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
		super.onMeasure(widthMeasureSpec, heightMeasureSpec);
	}
	
	/**
	 * 用Xml创建时最先被执行
	 * @param context
	 * @param attribute
	 */
	public CustomProgressView(Context context, AttributeSet attribute) {
		super(context, attribute);
	}
	
	
	@Override
	protected void onFinishInflate() {
	
		super.onFinishInflate();
		
	}
	@Override
	protected void onSizeChanged(int w, int h, int oldw, int oldh) {
		
		height = h;
		width = w;
		latticeHeight = height /maxheight;
		super.onSizeChanged(w, h, oldw, oldh);
	}
	private void initPaint() {
		p = new Paint();
		p.setColor(Color.BLUE);// 设置红色
	}
	int waterIndex;
	int oilIndex;

	@Override
	protected void onDraw(Canvas canvas) {
		
		initPaint();
	
		super.onDraw(canvas);
		if (color==0) {
			color = ColorFragment.yanseId(1);
		}
		oilRatio = oilRatio > 100 ? 100 : oilRatio;
		//oilRatio = 100;
		
		waterRatio = waterRatio > 100 ? 100 : waterRatio;
		//waterRatio=100;
		// 油比例 10~130像素之间 120x1 120x0.01 12 130-12
//		drawRect(canvas, (int) (height - height
//				* (oilRatio / 100f)), color);

		// 水比例
//		drawRect(canvas, (int) (height - height
//				* (waterRatio / 100f)),new Color().rgb(193, 210, 240));
		oilIndex = oilRatio*maxheight/100;//油占几格  2
		waterIndex = waterRatio*maxheight/100;//水占几格 1
//		//Log.i(TAG, "onDraw: 水占"+waterIndex+"油占"+oilIndex);

		//17 18 19   o  13
		for (int i=(maxheight-oilIndex);i<maxheight;i++){
			if((maxheight-i)> waterIndex) {  //19-1=18
				drawLattice(canvas, i,color);
			}else{
				drawLattice(canvas, i,waterColor);
			}
//			//Log.i(TAG, "onDraw: 当前的索引"+i);
		}


	}
	@Override
	protected void onLayout(boolean changed, int left, int top, int right,
			int bottom) {
	
		super.onLayout(changed, left, top, right, bottom);
	}
	/**
	 * 画油罐进度条
	 * 
	 *            油比例 0~100
	 *            水比例 0~100
	 */
	private void drawRect(Canvas canvas, float oilRatio, int color) {
		
		if (oilRatio != height) {
						p.setColor(color);
			canvas.drawRect(0, oilRatio, width, height, p);// 正方形
		}

	}

	/*z*
	 * 
	 * @param oilRatio
	 * @param waterRatio
	 * @param color
	 */
	public void setCoord(int oilRatio, int waterRatio, int color) {
		this.oilRatio = oilRatio;
		this.waterRatio = waterRatio;
		this.color = color;

	}
	 /** 
     * 根据手机的分辨率从 dp 的单位 转成为 px(像素) 
     */  
    public static int dip2px(Context context, float dpValue) {
        final float scale = context.getResources().getDisplayMetrics().density;  
        return (int) (dpValue * scale + 0.5f);
    }

	/**
	 *
	 * @param canvas 画布
	 * @param index 画在什么位置 1~20 颜色
     */
	public void drawLattice(Canvas canvas, int index, int color){
		int top =(int) (index*latticeHeight);
		int boom = (int)(top+latticeHeight);
//		//Log.i(TAG, "drawLattice:左"+0+"上"+top+"右"+width+"下"+boom);
		p.setColor(color);
		canvas.drawRect(0,top,width,boom,p);
	}
}
