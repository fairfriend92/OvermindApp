import java.io.Serializable;

public class GrayscaleCandidate implements Serializable {
	private float[] grayscalePixels;
	private int particleTag;
	
	public GrayscaleCandidate(float[] grayscalePixels, int particleTag) {
		this.grayscalePixels = new float[grayscalePixels.length];
        System.arraycopy(grayscalePixels, 0, this.grayscalePixels, 0, grayscalePixels.length);
        this.particleTag = particleTag;
	}

}
