package bge23.spectrogramandroid;

import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

public class SpectroFragment extends Fragment {
	
	private Button resumeButton;
	private LiveSpectrogramSurfaceView lssv;
	private TextView leftTimeTextView;
	private TextView rightTimeTextView;
	private TextView bottomFreqTextView;
	private TextView topFreqTextView;

	public SpectroFragment() {
	}

	@Override
	public View onCreateView(LayoutInflater inflater, ViewGroup container,
			Bundle savedInstanceState) {
		View rootView = inflater.inflate(
				R.layout.fragment_record,
				container, false);
		Log.d("", "LSSV INFLATED");
		lssv = (LiveSpectrogramSurfaceView)rootView.findViewById(R.id.lssv);
		
		resumeButton = (Button)rootView.findViewById(R.id.button_resume);
		resumeButton.setOnClickListener(new OnClickListener() {
			@Override 
			public void onClick(View arg0) {
				lssv.resumeScrolling();
			}
		});
		resumeButton.setVisibility(View.GONE);
		
		leftTimeTextView = (TextView)rootView.findViewById(R.id.time_text_left);
		rightTimeTextView = (TextView)rootView.findViewById(R.id.time_text_right);
		bottomFreqTextView = (TextView)rootView.findViewById(R.id.freq_text_bottom);
		topFreqTextView = (TextView)rootView.findViewById(R.id.freq_text_top);

		bottomFreqTextView.setText("0 kHz");
		rightTimeTextView.setText("0 sec");
		
		lssv.setLeftTimeTextView(leftTimeTextView);
		lssv.setTopFreqTextView(topFreqTextView);
		lssv.setResumeButton(resumeButton);
		
		return rootView;
	}
	
	@Override
	public void onPause() {
		super.onPause();
		lssv.pauseScrolling();
	}
	
	@Override
	public void onResume() {
		super.onResume();
		lssv.resumeFromPause();
	}
}