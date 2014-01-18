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

	private boolean running = false;
	public static final int SAMPLE_RATE = 16000; //default 44100, 11025, 22050
	private int CONTRAST = 400;
	private double maxAmplitude = 1; //max amplitude seen so far
	private short[][] micBuffers; //array of buffers so that different frames can be being processed while others are read in 
	private int numBuffers = 100; //number of buffers to maintain at once
	private int readSize;
	private int samplesPerWindow = 300; //usually around 1000
	private AudioRecord mic;
	private int audioWindowsAdded = 0;
	private int bitmapWindowsAdded = 0;
	private Thread audioThread;
	private Thread bitmapThread;
	private int[] colours;
	//private Semaphore bitmapsReady;
	private int nextBitmap = 0;


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
		
		readSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
		
		micBuffers = new short[numBuffers][samplesPerWindow];
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
			int currentBuffer = audioWindowsAdded % numBuffers;
			readUntilFull(micBuffers[currentBuffer], 0, samplesPerWindow); //request samplesPerWindow shorts be written into the next free microphone buffer
			double[] toAdd = new double[samplesPerWindow]; //create a new double-array to store the double-converted data
			for (int i = 0; i < samplesPerWindow; i++) { //convert the short data into double
				toAdd[i] = (double)micBuffers[currentBuffer][i];
				
			}
			synchronized(audioWindows) {
				audioWindows.add(toAdd);
			}
			//System.out.println("Audio window "+audioWindowsAdded+" added to list.");
			audioWindowsAdded++;
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
	
	public void fillBitmapList() { 				//TODO prev AND next instead?
		/*
		 * When some audio data is ready, perform the short-time Fourier transform on it and 
		 * then convert the results to a bitmap, which is then stored in a list, ready to be displayed.
		 */
		double[] previousTransformWindow = new double[samplesPerWindow];
		while (running) {
			int currentAudioWindow = audioWindowsAdded; //concurrency - only read once in case value changes during procedure
			if (bitmapWindowsAdded < currentAudioWindow) { //only do work if there is a new audio window to use
				//System.out.println("Bitmap windows added: "+bitmapWindowsAdded+", audio windows added: "+audioWindowsAdded+". Adding bitmap window.");
				double[] currentTransformWindow = new double[samplesPerWindow*2]; //double the size because of empty space needed for FFT
				synchronized(audioWindows) { //don't allow array copying to occur while the audio thread is writing into the ArrayList
					for (int i = 0; i < samplesPerWindow; i++) { //copy oldest unseen audio window into a new array for transforming
						currentTransformWindow[i] = audioWindows.get(bitmapWindowsAdded)[i];
					}
				}
				hammingWindow(currentTransformWindow); //apply Hamming window before performing STFT
				spectroTransform(currentTransformWindow); //do the STFT on the copied data
				
				double[] compositeWindow = new double[currentTransformWindow.length]; //new array for a 'composite' window (take neighbouring windows into account for smoothing effect)
				
				if (bitmapWindowsAdded != 0) { //don't use previous window for smoothing if there isn't one
					for (int i = 0; i < samplesPerWindow; i++) {
						compositeWindow[i] = previousTransformWindow[i] + currentTransformWindow[i];
					}
				} else compositeWindow = currentTransformWindow;
				
				int[] bitmapToAdd = new int[samplesPerWindow];
				for (int i = 0; i < samplesPerWindow; i++) {
					//System.out.print(compositeWindow[i] +" ");
					//int val = (int)(cappedValue2(compositeWindow[i])+cappedValue(compositeWindow[i]))/2; //TODO 1 or 2?
					int val = cappedValue(compositeWindow[i]);
					bitmapToAdd[samplesPerWindow-i-1] = colours[val]; //fill upside-down because y=0 is at top of screen
				}
				//System.out.println();
				bitmapWindows.add(bitmapToAdd);
				//System.out.println("Bitmap window "+bitmapWindowsAdded+" added to list.");
				bitmapWindowsAdded++;				
				previousTransformWindow = currentTransformWindow;
				//bitmapsReady.release(); //add one to the semaphore
			}
		}
	}
	
	public boolean isRunning() {
		return running;
	}
	
	public void setRunning(boolean b) { //TODO need?
		running = b;
	}
	
	public int getSamplesPerWindow() {
		return samplesPerWindow;
	}
	
	public int getBitmapWindowsAdded() {
		return bitmapWindowsAdded;
	}

	public int[] getBitmapWindow(int index) {
		/*
		 * This method returns a reference to the bitmap in the list which corresponds to the given index,
		 * allowing other classes (e.g. LiveSpectrogramSurfaceView) to retrieve individual generated bitmaps.
		 */
		if (index > bitmapWindowsAdded) return null;
		return bitmapWindows.get(index);
	}
	
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

}