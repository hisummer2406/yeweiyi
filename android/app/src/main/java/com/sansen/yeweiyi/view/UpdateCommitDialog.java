package com.sansen.yeweiyi.view;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.EditText;

import com.sansen.yeweiyi.R;
import com.sansen.yeweiyi.application.MyApplication;
import com.sansen.yeweiyi.bean.OilWork;
import com.sansen.yeweiyi.db.CommitWork;
import com.sansen.yeweiyi.manager.DBDataManager;

import org.apache.commons.lang3.StringUtils;

import java.util.List;

public class UpdateCommitDialog extends Activity implements OnClickListener {
	private EditText item_et_dialog_weight;
	private EditText item_et_dialog_volume;
	private EditText item_et_dialog_jie_height;
	private EditText item_et_dialog_jiao_height;
	private Button dialog_commit_update;
	private Button dialog_commit_cancel;
	private OilWork oilWork;
	private DBDataManager dbManager;
	private int oilCanId;
	
	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.item_commit_work);
		overridePendingTransition(0,0);
		Bundle bundle = getIntent().getBundleExtra("bundle");
		oilCanId = bundle.getInt("id");
		oilWork = (OilWork) bundle.getSerializable("obj");
		item_et_dialog_volume = (EditText) findViewById(R.id.item_et_dialog_volume);
		item_et_dialog_weight = (EditText) findViewById(R.id.item_et_dialog_weight);
		item_et_dialog_jie_height = (EditText)findViewById(R.id.item_et_dialog_jie_height);
		item_et_dialog_jiao_height = (EditText)findViewById(R.id.item_et_dialog_jiao_height);
		
		item_et_dialog_jie_height.setHint(oilWork.getBeginHeight()+"");
		item_et_dialog_jiao_height.setHint(oilWork.getEndHeight()+"");
		item_et_dialog_volume.setHint(oilWork.getShellVolume()+"");
		item_et_dialog_weight.setHint(oilWork.getShellWeight()+"");
		dialog_commit_update = (Button) findViewById(R.id.dialog_commit_update);
		dialog_commit_cancel = (Button) findViewById(R.id.dialog_commit_cancel);
		dialog_commit_cancel.setOnClickListener(this);
		dialog_commit_update.setOnClickListener(this);
		dbManager = MyApplication.getInstance().getmDBManager();
	}
	
	@Override
	public void onClick(View v) {
		switch (v.getId()) {
		case R.id.dialog_commit_update:
			String volume = item_et_dialog_volume.getText().toString();
			String weight = item_et_dialog_weight.getText().toString();
			String beginHight = item_et_dialog_jie_height.getText().toString();
			String endHeight = item_et_dialog_jiao_height.getText().toString();
			CommitWork commitWork = (CommitWork) dbManager.findObjectByID(CommitWork.class, oilCanId);
			List<OilWork> oilWorks= dbManager.stringToArray(commitWork.getInfo(), OilWork[].class);
			
			int pos=0;
			for (OilWork oilWork1 : oilWorks) {
				if (oilWork1.getOilId()==oilWork.getOilId()) {
					break;
				}else {
					pos++;
				}
			}
			
			if (StringUtils.isNotEmpty(beginHight)) {
				oilWork.setBeginHeight(Double.parseDouble(beginHight));
			}
			if (StringUtils.isNotEmpty(endHeight)) {
				oilWork.setEndHeight(Double.parseDouble(endHeight));
			}
			if (StringUtils.isNotEmpty(volume)) {
				oilWork.setShellVolume(Double.parseDouble(volume));
			}
			if (StringUtils.isNotEmpty(weight)) {
				oilWork.setShellWeight(Double.parseDouble(weight));
			}
			
			oilWorks.set(pos,oilWork);
			String result = dbManager.getListToStringByGson(oilWorks);
			commitWork.setInfo(result);
		
			dbManager.UpdateObject(commitWork);
			setResult(100);
			finish();
			break;
		case R.id.dialog_commit_cancel:
			finish();
			break;
		}
	}
	@Override
	protected void onPause() {
		// TODO Auto-generated method stub
		super.onPause();
		overridePendingTransition(0,0);
	}
}