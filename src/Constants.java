
public class Constants {	    
    /* Particle flags */
    static final int UNDETERMINED = 0;
    
    /* Application constants */
    static int STIMULATION_LENGTH = 200; // Length in ms of the period during which the inputs are presented to the network.
    static int DELTA_TIME = 10; 
    static int MAX_PIC_PIXELS = 1024; // The maximum number of pixels a sample image can be made of.
    
    /* Network related constants */
	static final int IPTOS_THROUGHPUT = 0x08;
	static final int MUON_DETECTOR_SERVER_PORT = 4197; // Port for the sending of pics from the MuonDetector application.
    static final int IPTOS_RELIABILITY = 0x04;
    
    /* Math constants */
    static final int NANO_TO_MILLS_FACTOR = 1000000;
}
