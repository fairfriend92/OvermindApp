package overmind_app;
import java.io.Serializable;

public class GrayscaleCandidate implements Serializable {
	public float[] grayscalePixels;
	public int lable;
	
	public GrayscaleCandidate(float[] grayscalePixels, int lable) {
		this.grayscalePixels = new float[grayscalePixels.length];
        System.arraycopy(grayscalePixels, 0, this.grayscalePixels, 0, grayscalePixels.length);
        this.lable = lable;
	}
}
