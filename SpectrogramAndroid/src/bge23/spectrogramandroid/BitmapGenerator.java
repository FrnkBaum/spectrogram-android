package bge23.spectrogramandroid;

import java.util.ArrayList;
import java.util.concurrent.Semaphore;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;
import edu.emory.mathcs.jtransforms.fft.DoubleFFT_1D;

public class BitmapGenerator {

	/*
	 * Class which handles most of the 'clever stuff' behind the spectrogram display; taking in audio sample data
	 * from the microphone and processing it to give a list of bitmaps (each representing one window of the audio),
	 * ready to be displayed. Two threads are created: one to pull in data from the microphone ('audioThread') and 
	 * another to convert this data into a bitmap as soon as it becomes available ('bitmapThread').
	 */

	private ArrayList<double[]> audioWindows = new ArrayList<double[]>();//list of 1D double arrays, each representing a window worth of audio samples
	private ArrayList<int[]> bitmapWindows = new ArrayList<int[]>(); //list of 1D arrays of pixel values for the bitmap. each element of this list represents the array of pixel values for one [composite] window
	public static final int SAMPLE_RATE = 16000; //options are 11025, 22050, 16000, 44100
	public static final int SAMPLES_PER_WINDOW = 300; //usually around 300
	private final int MIC_BUFFERS = 100; //number of buffers to maintain at once


	//number of windows that can be held in the arrays at once before older ones are deleted. Time this represents is
	// WINDOW_LIMIT*SAMPLES_PER_WINDOW/SAMPLE_RATE, e.g. 10000*300/16000 = 187.5 seconds.
	protected static final int WINDOW_LIMIT = 5000; //usually around 10000 

	//Storage for audio and bitmap windows is pre-allocated, and the quantity is determined by
	// WINDOW_LIMIT*SAMPLES_PER_WINDOW*(bytes per int + bytes per double),
	// e.g. 10000*300*(4+8) = 34MB

	private double[][] audioWindowsA = new double[WINDOW_LIMIT][SAMPLES_PER_WINDOW];
	private int[][] bitmapWindowsA = new int[WINDOW_LIMIT][SAMPLES_PER_WINDOW];

	private boolean running = false;


	//	private int CONTRAST = 400;
	private double maxAmplitude = 1; //max amplitude seen so far
	private short[][] micBuffers = new short[MIC_BUFFERS][SAMPLES_PER_WINDOW];; //array of buffers so that different frames can be being processed while others are read in 
	private AudioRecord mic;
	private int audioWindowsAdded = 0; //TODO needed?
	private int bitmapWindowsAdded = 0;
	private Thread audioThread;
	private Thread bitmapThread;
	private int[] colours;
	private Integer audioCurrentIndex = 0; //keep track of where in the audioWindows array we have most recently written to
	private int bitmapCurrentIndex = 0;
	private boolean audioHasLooped = false; //true if we have filled the entire audioWindows array and are now looping round, hence old values can be read from later in the array
	private boolean bitmapHasLooped = false; //same as above, for bitmapWindows array
	private Semaphore audioReady = new Semaphore(0);
	private Semaphore bitmapsReady = new Semaphore(0);
	private int lastBitmapRequested = 0; //keeps track of the most recently requested bitmap window


	public BitmapGenerator(int colourMap) {
		//bitmapsReady = new Semaphore(0);
		colours = new int[256];

		switch (colourMap) {
		case 0: colours = HeatMap.greyscale(); break;
		case 1: colours = HeatMap.blueGreenRed(); break;
		case 2: colours = HeatMap.bluePinkRed(); break;
		case 3: colours = HeatMap.blueOrangeYellow(); break;
		case 4: colours = HeatMap.yellowOrangeBlue(); break;
		case 5: colours = HeatMap.blackGreen(); break;
		}

		int readSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
		mic = new AudioRecord(MediaRecorder.AudioSource.MIC,SAMPLE_RATE,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT, readSize*2);
	}

	public void start() {
		mic.startRecording();
		running = true;
		audioThread = new Thread(new Runnable(){
			public void run() {
				fillAudioList();
			}
		});

		bitmapThread = new Thread(new Runnable(){
			public void run() {
				fillBitmapList();
			}
		});

		audioThread.start();

		bitmapThread.start();
	}

	public void stop() {
		running = false;
		mic.stop();
	}

	public void fillAudioList() {
		/*
		 * When audio data becomes available from the microphone, convert it into a double-array, ready
		 * for the FFT. Store it in a list so that it remains available in case the user chooses to replay 
		 * certain sections.
		 */
		while (running) {
			int currentBuffer = audioWindowsAdded % MIC_BUFFERS;
			readUntilFull(micBuffers[currentBuffer], 0, SAMPLES_PER_WINDOW); //request samplesPerWindow shorts be written into the next free microphone buffer
			double[] toAdd = new double[SAMPLES_PER_WINDOW]; //create a new double-array to store the double-converted data
			for (int i = 0; i < SAMPLES_PER_WINDOW; i++) { //convert the short data into double
				toAdd[i] = (double)micBuffers[currentBuffer][i];
			}

			if (audioCurrentIndex == audioWindowsA.length) {
				//if entire array has been filled, loop and start filling from the start
				Log.d("", "Adding audio item "+audioCurrentIndex+" and array full, so looping back to start");
				synchronized(audioCurrentIndex) {
					audioCurrentIndex = 0;
				}
				audioHasLooped = true;
			}
			synchronized(audioWindowsA) {
				audioWindowsA[audioCurrentIndex] = toAdd;
			}
			synchronized(audioCurrentIndex) { //don't modify this when it might be being read by another thread
				audioCurrentIndex++;
				audioReady.release();
			}
			Log.d("Audio thread","Audio window "+audioCurrentIndex+" added.");
			//audioWindowsAdded++;
		}
	}

	private void readUntilFull(short[] buffer, int offset, int spaceRemaining) {
		/*
		 * The 'read' method supplied by the AudioRecord class will not necessarily fill the destination
		 * buffer with samples if there is not enough data available. This method always returns a full array by
		 * repeatedly calling the 'read' method until there is no space left.
		 */
		int samplesRead;
		while (spaceRemaining > 0) {
			samplesRead = mic.read(buffer, offset, spaceRemaining);
			spaceRemaining -= samplesRead;
			offset += samplesRead;
		}
	}

	public void fillBitmapListOld() { 
		/*
		 * When some audio data is ready, perform the short-time Fourier transform on it and 
		 * then convert the results to a bitmap, which is then stored in a list, ready to be displayed.
		 */
		while (running) {
			int windowsAvailable;
			int newestWindow;
			synchronized(audioCurrentIndex) {
				windowsAvailable = audioReady.availablePermits();
				newestWindow = audioCurrentIndex-1; //audioCurrentIndex itself won't have been filled yet, so -1
			}
			try {
				Log.d("Bitmap thread", windowsAvailable+" permits available. Newest window is "+newestWindow);
				audioReady.acquire(windowsAvailable);
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
			if (windowsAvailable > newestWindow) {
				//has looped, must read from end of the array
				int startingIndex = audioWindowsA.length-(windowsAvailable-newestWindow)+1; //+1 because of <= in second part
				for (int i = 0; i < windowsAvailable-newestWindow-1; i++) {
					processAudioWindow(audioWindowsA[startingIndex+i]);
					Log.d("Bitmap thread","Audio window "+(startingIndex+i)+ " processed. ");
				}
				windowsAvailable = newestWindow;


				for (int i = 0; i <= newestWindow; i++) {
					processAudioWindow(audioWindowsA[i]);
					Log.d("Bitmap thread","Audio window "+i+ " processed. ");
				}
			}

			for (int i = newestWindow - windowsAvailable+1; i <= newestWindow; i++) {
				processAudioWindow(audioWindowsA[i]);
				Log.d("Bitmap thread","Audio window "+i+ " processed. ");
			}

		}
	}
	
	public void fillBitmapList() { 
		/*
		 * When some audio data is ready, perform the short-time Fourier transform on it and 
		 * then convert the results to a bitmap, which is then stored in a list, ready to be displayed.
		 */
		while (running) {

			try {
				audioReady.acquire();
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}

			processAudioWindow(audioWindowsA[bitmapCurrentIndex]);
			Log.d("Bitmap thread","Audio window "+(bitmapCurrentIndex)+ " processed. ");
		}

	}

	private void processAudioWindow(double[] samples) { //TODO prev and next
		//double[] previousTransformWindow = new double[SAMPLES_PER_WINDOW];
		
		double[] fftSamples = new double[SAMPLES_PER_WINDOW*2]; //need half the array to be empty for FFT
		for (int i = 0; i < SAMPLES_PER_WINDOW; i++) {
			fftSamples[i] = samples[i];
		}
		hammingWindow(fftSamples); //apply Hamming window before performing STFT
		spectroTransform(fftSamples); //do the STFT on the copied data

		int[] bitmapToAdd = new int[SAMPLES_PER_WINDOW];
		for (int i = 0; i < SAMPLES_PER_WINDOW; i++) {
			int val = cappedValue(fftSamples[i]);
			bitmapToAdd[SAMPLES_PER_WINDOW-i-1] = colours[val]; //fill upside-down because y=0 is at top of screen
		}


		bitmapWindowsA[bitmapCurrentIndex] = bitmapToAdd;
		bitmapCurrentIndex++;
		bitmapsReady.release();
		
		if (bitmapCurrentIndex == bitmapWindowsA.length) {
			bitmapCurrentIndex = 0;
			bitmapHasLooped = true;
		}
	}

	public boolean isRunning() {
		return running;
	}

	public void setRunning(boolean b) { //TODO need?
		running = b;
	}

	//	public int[] getBitmapWindow(int index) { TODO
	//		/*
	//		 * This method returns a reference to the bitmap in the list which corresponds to the given index,
	//		 * allowing other classes (e.g. LiveSpectrogramSurfaceView) to retrieve individual generated bitmaps.
	//		 */
	//		if ()
	//	}

	private int cappedValue(double d) {
		/*
		 * This method will return an integer capped at 255 representing the magnitude of the
		 * given double value, d, relative to the highest amplitude seen so far. The amplitude values
		 * provided use a logarithmic scale but this method converts these back to a linear scale, 
		 * more appropriate for pixel colouring.
		 */
		if (d < 0) return 0;
		if (d > maxAmplitude) {
			maxAmplitude = d;
			return 255;
		}
		return (int)(255*(Math.log1p(d)/Math.log1p(maxAmplitude)));
	}

	private void hammingWindow(double[] samples) {
		/*
		 * This method applies an appropriately-sized Hamming window to the provided array of 
		 * audio sample data.
		 */
		int m = samples.length/2;
		double[] hamming = new double[samples.length];
		double r = Math.PI/(m+1);
		for (int i = -m; i < m; i++) {
			hamming[m + i] = 0.5 + 0.5 * Math.cos(i * r);
		}

		//apply windowing function through multiplication with time-domain samples
		for (int i = 0; i < samples.length; i++) {
			samples[i] *= hamming[i]; 
		}
	}

	private void spectroTransform(double[] paddedSamples) {
		/*
		 * This method modifies the provided array of audio samples in-place, replacing them with 
		 * the result of the short-time Fourier transform of the samples.
		 *
		 * See 'realForward' documentation of JTransforms for more information on the FFT implementation.
		 */
		DoubleFFT_1D d = new DoubleFFT_1D(paddedSamples.length / 2); //DoubleFFT_1D constructor must be supplied with an 'n' value, where n = data size

		d.realForward(paddedSamples);

		//Now the STFT has been calculated, need to square it:

		for (int i = 0; i < paddedSamples.length / 2; i++) {
			paddedSamples[i] *= paddedSamples[i];
		}
	}

	public int getSampleRate(){
		return SAMPLE_RATE;
	}

	protected int getBitmapWindowsAvailable() {
		return bitmapsReady.availablePermits();
	}

	protected int[] getNextBitmap() {
		/*
		 * Returns a REFERENCE to the next bitmap window to be drawn, assuming that the caller will draw it before the bitmap 
		 * creating thread overwrites it (the array size is large - drawing thread would have to be thousands of windows behind the 
		 * creator thread). This potentially dangerous behaviour could be fixed with locks at the cost of performance.
		 */
		try {
			bitmapsReady.acquire(); //block until there is a bitmap to return
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		if (lastBitmapRequested == bitmapWindowsA.length) lastBitmapRequested = 0; //loop if necessary
		Log.d("Spectro","Bitmap "+lastBitmapRequested+" requested");
		int[] ret = bitmapWindowsA[lastBitmapRequested];
		lastBitmapRequested++;
		return ret;
	}

}