
public class MuonTeacherConst {	    
    /* Particle flags */
    static final int UNDETERMINED = 0;
    static final int TRACK = 1;
    static final int SPOT = 3;
    
    /* Application constants */
    static int STIMULATION_LENGTH = 2000; // Length in ms (real time) of the period during which the inputs are presented to the network.
    static int DELTA_TIME = 10; 
    static int PAUSE_LENGTH = 4000; 
    static short MAX_PIC_PIXELS = 1024; // The maximum number of pixels a sample image can be made of.
    static float MEAN_RATE_INCREMENT = 0.005f; // Inverse of the number of samples need to compute the mean firing rate.
    
    /* Network related constants */
	static final int IPTOS_THROUGHPUT = 0x08;
	static final int MUON_DETECTOR_SERVER_PORT = 4197; // Port for the sending of pics from the MuonDetector application.
	static final int APP_UDP_PORT = 4197; // Port through which the app send the Poisson spikes trains to the clients. 
    static final int IPTOS_RELIABILITY = 0x04;
    
    /* Math constants */
    static final int NANO_TO_MILLS_FACTOR = 1000000;
    static final float MIN_WEIGHT = 0.0078f;
    static final int SIZE_OF_FLOAT = 4;
    static final int SIZE_OF_BYTE = 1;
    static final int SIZE_OF_INT = 4;
    
    /* Resource constants */
	static final short MAX_DATA_BYTES = 8192;
}
