<h1>Overmind</h1>

Overmind is a spiking neural network (SNN) simulator for Android devices with a distributed architecture. 

The neural network is organized in sub-networks. Each sub-network is simulated on a separate terminal. Sub-networks exchange spikes over the internet using the User Datagram Protocol (UDP).

Overmind consists of 2 main components: 
  1) A client application, to be run on the Android terminals, that simulates the local sub-network and takes care of communications with other client applications
  2) A server application, that runs on a desktop pc and manages the topology of the virtual network that is made of the client terminals 
  
Communications among terminals are direct, i.e. the terminals form of a P2P network. The server facilitates the "UDP hole punching" technique to make direct communications among clients possible. A brief overview of the simulator can be found here; more technical details can be found here.

<h2>OvermindApp</h2>

This is an external application that interacts with the OvermindServer, in order to read the spikes generated by the sub-networks. 