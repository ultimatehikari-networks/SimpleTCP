package stcp;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.Timer;
import java.util.TimerTask;

public class SimpleServerSocket {
	private DatagramSocket socket;
	private static final int timeout  = 1000;
	private int port;
	private InetAddress address;
	private int childPort;
	DatagramPacket packet = null;

	
	public SimpleServerSocket(int port_) throws SocketException, UnknownHostException {
		port = port_;
		childPort = port  + 1;
		address = InetAddress.getByName("localhost");
		socket = new DatagramSocket(port_);
	}
	
	public SimpleSocket accept(){
		Timer timer = new Timer();
		int[] dest = recvSYN();
		sendSYNACK(dest, timer);
		int recvACK = recvACK(dest, timer);
		//TODO no penalties for exceptions here;
		//single-threaded also
		System.out.println("SERVER: connected to " + dest[0]);
		SimpleSocket res = null;
		try {
			res = new SimpleSocket(childPort, recvACK);
			res.softConnect(InetAddress.getByName("localhost"), 5000);
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		childPort++;
		return res;
	}
	private int[] recvSYN() {
		DatagramPacket recvpacket = new DatagramPacket(new byte[1024], 1024);
		try {
			socket.receive(recvpacket);
		} catch (IOException e) {
			e.printStackTrace();
		}
		int destPort = recvpacket.getPort();
		int destSeq = recvpacket.getData()[1];
		return new int[] {destPort, destSeq};
	}
	
	private void sendSYNACK(int[] dest, Timer timer) {
		try {
			packet = PacketWrapper.wrap(
					ByteBuffer.allocate(4).putInt(childPort).array(),
					dest[1] + 1,
					0,
					Flags.SYNACK,
					address,
					dest[0]);
			socket.send(packet);
		} catch (InstantiationException | IOException e) {
			e.printStackTrace();
		}
		
		timer.schedule(
			new TimerTask() {
				@Override
				public void run() {
					try {
						System.out.println("SERVER: timer elapsed");
						socket.send(packet);
					} catch (IOException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
				}
			},
			timeout);
	}
	
	private int recvACK(int[] dest, Timer timer) {
		DatagramPacket recvpacket = new DatagramPacket(new byte[1024], 1024);
		int recvDest, recvACK = 0;
		do {
			try {
				socket.receive(recvpacket);
			} catch (IOException e) {
				e.printStackTrace();
			}
			recvDest = recvpacket.getPort();
			recvACK = recvpacket.getData()[0];
			System.out.println("SERVER: got 3rd ack");
		} while(recvDest != dest[0] || recvACK != 1);
		timer.cancel();
		return recvACK;
	}

	public void close() {
		//TODO
	}
}