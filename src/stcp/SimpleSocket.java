package stcp;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.Random;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.locks.ReentrantLock;

public class SimpleSocket {
	class Resender extends TimerTask {
		@Override
		public void run() {
			synchronized (sending) {
				log("Timer: elapsed");
				if(resendsIgnored == RESENDS_IGNORED_THRESHOLD) {
					log("Timer: detected dead receiver");
					markAllPacketsAsSent();
				}
				if (indexer.isBefore(base, end)) {
					log("Timer: base/end " + base + " " + end);
					
					refreshIgnoredResends();
					
					try {
						log("Timer: resending " + base + " : " 
							+ Flags.values()[Wrapper.getFlag(sending[base])]);
						socket.send(sending[base]);
					} catch (IOException e) {
						e.printStackTrace();
					}
					
					isTimerSet = true;
					timer.schedule(new Resender(), timeout);
				} else {
					log("Timer: stopped");
				}
			}
		}

		private void markAllPacketsAsSent() {
			end = base;
		}
		
		private void refreshIgnoredResends() {
			if(base == lastBase) {
				resendsIgnored++;
			}else {
				resendsIgnored = 0;
				lastBase = base;
			}
			
		}
	}

	private static final int BUFFER_SIZE = 256;
	private static final boolean LOG_LEVEL = true;
	private final CycleIndexer indexer = new CycleIndexer(BUFFER_SIZE);

	private int base = 0;
	private int end = 0; // nextseqnum
	private int currentACK = 0;

	private final ReentrantLock connectLock = new ReentrantLock();

	private DatagramPacket[] sending = new DatagramPacket[BUFFER_SIZE];
	private DatagramPacket[] receiving = new DatagramPacket[BUFFER_SIZE];
	private ArrayBlockingQueue<byte[]> recieved = new ArrayBlockingQueue<byte[]>(BUFFER_SIZE);

	private Thread rThread;
	private DatagramSocket socket;
	private int socketTimeout = 1000;
	private SimpleSocketAddress address  = new SimpleSocketAddress();

	private Timer timer = new Timer();
	private boolean isTimerSet = false;
	private int timeout = 100;
	private int lastBase = 0;
	private final int RESENDS_IGNORED_THRESHOLD = 8;
	private int resendsIgnored = 0;

	boolean isRunning = true;
	boolean isConnected = false;
	private Random random = new Random();

	class ReadLoop implements Runnable {
		private DatagramPacket packet;
		private int ackindex;
		private int index;
		private int flag;

		@Override
		public void run() {
			while (isRunning || indexer.isBefore(base, end) || isConnected) {
				try {
					packet = new DatagramPacket(new byte[1024], 1024);
					socket.receive(packet);
					fillHeaders(packet.getData());
					synchronized (receiving) {
						if (flag != Flags.ACK.ordinal() && receiving[index] == null && currentACK <= index) {
							receiving[index] = packet;
							// log("taking " + index);
						} else {
							// log("discarding " + index);
						}
						pushRecieved();
						if (Wrapper.isEligibleForACK(packet)) {
							send(new byte[1], Flags.ACK);
						}
					}
					handleFlag();

				} catch (SocketTimeoutException e) { 
					// no tcp-keepalive packet 'cause we dont have them
					// in scenario of 10 packets;
					break;
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
			closeResources();
		}

		private void fillHeaders(byte[] data) {
			ackindex = Wrapper.getAckindex(packet);
			index = Wrapper.getSeqindex(packet);
			flag = Wrapper.getFlag(packet);
			log("        got " + Wrapper.toHeadersString(packet)+ " fr " + address.getDestPort());
		}

		private void handleFlag() {
			if (flag == Flags.ACK.ordinal()) {
				synchronized (sending) {
					for (int i = base; i < ackindex; i++) {
						sending[i] = null;
					}
				}
				base = Math.max(base, ackindex);
			}

			if (flag == Flags.FIN.ordinal()) {
				// means other side stopped sending useful packets
				log("got FIN");
				isRunning = false;
			}
		}
	}

	SimpleSocket(int port, int destACK) throws IOException {
		// for server use, so packet-wide
		this(port);
		currentACK = destACK;
	}

	public SimpleSocket(int port) throws IOException {
		address.setSourcePort(port);
		socket = new DatagramSocket(port);
		connectLock.lock();
	}

	public byte[] recieve() throws SocketException, InterruptedException {
		if (isConnected || base < end || recieved.size() > 0) {
			byte[] res;
			res = recieved.take();
			return res;
		} else {
			throw new SocketException("Socket is closed");
		}
	}

	public void send(byte[] data) throws InterruptedException, SocketException, IOException {
		connectLock.lock();
		try {
			send(data, Flags.NOP);
		} finally {
			connectLock.unlock();
		}
	}

	private void send(byte[] data, Flags flag) throws SocketException, IOException {
		// actually can check for is running here
		DatagramPacket packet;
		packet = Wrapper.wrap(data, currentACK, end, flag, address);
		if (random.nextInt(10) > 6) {
			log("NOT sending " + Wrapper.toHeadersString(packet) + " to " + address.getDestPort());
		} else {
			log("    sending " + Wrapper.toHeadersString(packet) + " to " + address.getDestPort());
			socket.send(packet);
		}
		// fictional, but since we have no payload for acks
		if (flag != Flags.ACK) {
			synchronized (sending) {
				sending[end] = packet;
				end = indexer.getNext(end);
				if (!isTimerSet) {
					isTimerSet = true;
					timer.schedule(new Resender(), timeout);
				}
			}
		}
	}

	private void pushRecieved() {
		// acking n-th with n+1 ack
		while (receiving[currentACK] != null) {
			// log("pushing " + currentACK);
			if (Wrapper.hasPayload(receiving[currentACK])) {
				recieved.add(Wrapper.getPayload(receiving[currentACK]));
			}
			receiving[currentACK] = null;
			currentACK = indexer.getNext(currentACK);
		}
	}

	public void connect(InetAddress address_, int port) throws SocketException, IOException {
		address.setAddress(address_);
		address.setDestPort(port);
		try {
			sendSYN();
			int serverSeq = recvSYNACK();
			send3rdACK(serverSeq, port);

			isConnected = true;
			log("connected to " + address.getDestPort());
		} finally {
			connectLock.unlock();
		}
		// mb want to check for real connection but nah, take your 3-way handshake
		// (need timeout in recvSYNACK, but in our terms server always exists)
		socket.setSoTimeout(socketTimeout);
		rThread = new Thread(new ReadLoop());
		rThread.start();
		log("started " + rThread.toString());
	}

	private void sendSYN() throws IOException {
		send(new byte[1], Flags.SYN);
	}

	private int recvSYNACK() throws IOException {
		DatagramPacket packet = new DatagramPacket(new byte[10], 10);
		socket.receive(packet);
		address.setDestPort(Wrapper.getServerAcceptPort(packet));
		
		return Wrapper.getSeqindex(packet);
	}

	private void send3rdACK(int serverSeq, int serverListeningPort) throws SocketException, IOException {
		SimpleSocketAddress listenAddress = 
				new SimpleSocketAddress(
						address.getSourcePort(),
						serverListeningPort,
						address.getAddress()
						);
		socket.send(Wrapper.wrap(
				new byte[1],
				serverSeq + 1,
				base,
				Flags.ACK,
				listenAddress));
		base = indexer.getNext(base);
	}

	public void softConnect(InetAddress address_, int port) throws SocketException {
		address.setAddress(address_);
		address.setDestPort(port);
		isConnected = true;
		socket.setSoTimeout(socketTimeout);
		rThread = new Thread(new ReadLoop());
		rThread.start();
	}

	private void log(String s) {
		if (LOG_LEVEL) {
			System.out.println("[" + address.getSourcePort() + "]: " + s);
		}
	}

	private void closeResources() {
		socket.close();
		timer.cancel();
		log("Closed from " + Thread.currentThread().toString());
	}
	
	public void close() throws InterruptedException, IOException {
		// send fin when done writing
		// stop recv when got fin
		log("Closing from outer .close()");
		send(new byte[1], Flags.FIN);
		isConnected = false;
		// then getting ack and closing in readloop
		rThread.join();
	}

}
