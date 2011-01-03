package eu.jacquet80.rds.input;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.LinkedList;

import eu.jacquet80.rds.input.GroupReader.EndOfStream;
import eu.jacquet80.rds.input.group.FrequencyChangeEvent;
import eu.jacquet80.rds.input.group.GroupEvent;
import eu.jacquet80.rds.input.group.GroupReaderEvent;

public class TCPTunerGroupReader implements TunerGroupReader {
	private String name = "";
	private final BufferedReader reader;
	private final PrintWriter writer;
	private final LinkedList<GroupReaderEvent> groups = new LinkedList<GroupReaderEvent>();
	private boolean newGroups = false;
	private int freq;
	
	public TCPTunerGroupReader(String hostname, int port) throws IOException {
		Socket socket = new Socket(hostname, port);
		reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
		writer = new PrintWriter(socket.getOutputStream(), true);  // true for autoflush
	}
	
	@Override
	public synchronized GroupReaderEvent getGroup() throws IOException, EndOfStream {
		while(groups.size() == 0) {
			readUntil(null);
		}
		
		return groups.removeFirst();
	}

	@Override
	public boolean isStereo() {
		return false;
	}

	@Override
	public int setFrequency(int frequency) {
		writer.println("SET_FREQ " + frequency);
		return 0;
		//return getReportedFrequency();
	}

	@Override
	public int getFrequency() {
		//writer.println("GET_FREQ");
		//return getReportedFrequency();
		return 0;
	}
	
	private synchronized int getReportedFrequency() {
		try {
			readUntil("% Freq");
			return freq;
		} catch (IOException e) {
			return 0;
		} catch (EndOfStream e) {
			return 0;
		}
	}

	@Override
	public int mute() {
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	public int unmute() {
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	public int getSignalStrength() {
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	public void tune(boolean up) {
		writer.println(up ? "UP" : "DOWN");
	}

	@Override
	public void seek(boolean up) {
		writer.println("SEEK " + (up ? "UP" : "DOWN"));
	}

	@Override
	public String getDeviceName() {
		return "TCP" + name;
	}

	@Override
	public synchronized boolean newGroups() {
		boolean ng = newGroups;
		newGroups = false;
		return ng;
	}
	
	private synchronized String readUntil(String start) throws IOException, EndOfStream {
		String line;
		
		do {
			line = reader.readLine();
			if(line == null) throw new EndOfStream();
			
			GroupReaderEvent event = HexFileGroupReader.parseHexLine(line, 0);
			
			if(event instanceof GroupEvent) newGroups = true;
			else if(event instanceof FrequencyChangeEvent) {
				FrequencyChangeEvent fEvent = (FrequencyChangeEvent) event;
				freq = fEvent.frequency;
			}
			
			if(event != null) groups.addLast(event);
		} while(start != null && !line.startsWith(start));
		return line;
		
	}

}
