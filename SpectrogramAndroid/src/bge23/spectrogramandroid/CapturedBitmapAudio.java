package bge23.spectrogramandroid;

import java.io.Serializable;

public class CapturedBitmapAudio implements Serializable {
	/*
	 * A serializable class which allows for the audio data, bitmap data and parameters to be packaged up
	 * and unpackaged at the other end by the server.
	 */

	private static final long serialVersionUID = 1L;
	protected static final String EXTENSION = ".cba";
	private final double decLatitude;
	private final double decLongitude;
	private final String filename;
	private final int[] bitmapAsIntArray;
	private final byte[] wavAsByteArray;
	private final int bitmapWidth;
	private final int bitmapHeight;
	
	CapturedBitmapAudio(String filename, int[] bitmapAsIntArray, byte[] wavAsByteArray, int bitmapWidth, int bitmapHeight, double decLatitude, double decLongitude) {
		this.filename = filename;
		this.bitmapAsIntArray = bitmapAsIntArray;
		this.wavAsByteArray = wavAsByteArray;
		this.decLatitude = decLatitude;
		this.decLongitude = decLongitude;
		this.bitmapWidth = bitmapWidth;
		this.bitmapHeight = bitmapHeight;

	}
	
	public int[] getBitmapPixels() {
		int[] ret = new int[bitmapAsIntArray.length];
		for (int i = 0; i < bitmapAsIntArray.length; i++) {
			ret[i] = bitmapAsIntArray[i];
		}
		return ret;
	}
	
	public int getBitmapWidth() {
		return bitmapWidth;
	}
	
	public int getBitmapHeight() {
		return bitmapHeight;
	}

}