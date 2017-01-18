package org.dapnet.core.transmission;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.dapnet.core.Settings;
import org.dapnet.core.model.Transmitter.DeviceType;
import org.dapnet.core.transmission.TransmissionSettings.PagingProtocolSettings;

public class RaspagerClientProtocol implements TransmitterDeviceProtocol {

	private static final PagingProtocolSettings settings = Settings.getTransmissionSettings()
			.getPagingProtocolSettings();
	private int sequenceNumber = 0;

	private int incrementSequenceNumber() {
		int sn = sequenceNumber;
		sequenceNumber = (sequenceNumber + 1) % 256;

		return sn;
	}

	@Override
	public void handleWelcome(TransmitterDevice transmitterDevice, PrintWriter toServer, BufferedReader fromServer)
			throws TransmitterDeviceException, IOException, InterruptedException {
		// Fix problems with XOS
		sequenceNumber = 0;
		// Mostly adapted from Sven Jung
		// 1. Read SID

		// check if server is ready (timeout: 10s)
		int timeout = 0;
		while (!fromServer.ready() && timeout < 9) {
			Thread.sleep(1000);
			++timeout;
		}

		// still not ready or timeout
		if (!fromServer.ready() || timeout >= 10) {
			throw new TransmitterDeviceException("No data from Transmitter received.");
		}

		// check for non-empty message (timeout: 10s)
		timeout = 0;
		String msg = "";
		while (timeout < 9) {
			msg = fromServer.readLine();
			if (msg == null || msg.equals("")) {
				Thread.sleep(1000);
				++timeout;
			} else {
				break;
			}
		}

		// still no non-empty message or timeout
		if (msg == null || msg.isEmpty() || timeout >= 10) {
			throw new TransmitterDeviceException("No SID");
		}

		// [RasPager v1.0-SCP-#2345678]
		// Pattern sid = Pattern.compile("\\[(\\w+)
		// v(\\d+[.]\\d+)-SCP-#(\\d+)\\][ ]?\\d?[ ]?\\d?[ ]?\\d?[ ]?\\d?[
		// ]?\\d?[ ]?");

		// Get transmitter device type
		if (msg.startsWith("[RasPager")) {
			transmitterDevice.setDeviceType(DeviceType.RASPPAGER1);
		} else if (msg.startsWith("*/XOS")) {
			transmitterDevice.setDeviceType(DeviceType.XOS);
		} else if (msg.startsWith("[PR430")) {
			transmitterDevice.setDeviceType(DeviceType.PR430);
		} else if (msg.startsWith("[SDRPager")) {
			transmitterDevice.setDeviceType(DeviceType.SDRPAGER);
		} else if (msg.startsWith("[DV4mini")) {
			transmitterDevice.setDeviceType(DeviceType.DV4MINI);
		} else {
			throw new TransmitterDeviceException("Unknown device type received.");
		}

		// 2. Sync System Times
		// 2.a) get min_rtt
		int numberOfSyncLoops = settings.getNumberOfSyncLoops();
		long time_rtt_min = Long.MAX_VALUE;
		long time_adjust = 0;
		// send multiple time requests and pick shortest RTT
		for (int i = 0; i < numberOfSyncLoops; i++) {
			// send RadioServer system time
			long timemillis = System.currentTimeMillis();
			long seconds = timemillis / 1000;
			// Milliseconds as long of current second
			long deltaTimemillis = timemillis - seconds * 1000;
			// Time since last full minute in 0,1 s, lowest 16 bit
			// after 1 complete minute, counter will continue with 601, 602,...
			// up to 0xffff, than wrap to 0x0000
			long time_tx = (seconds * 10 + deltaTimemillis / 100) & 0xffff;
			// long time_tx = RadioServer.getSysTime();
			String time_string_server = String.format("%04x", time_tx);
			toServer.println(String.format("%s:%s", PagingMessageType.SYNCREQUEST.getValue(), time_string_server));

			// get response
			String resp = fromServer.readLine(); // 2:13d3:0026
			if (resp == null)
				throw new TransmitterDeviceException("No Sync Response");

			timemillis = System.currentTimeMillis();
			seconds = timemillis / 1000;
			deltaTimemillis = timemillis - seconds * 1000; // additional
															// milliseconds
			long time_rx = (seconds * 10 + deltaTimemillis / 100) & 0xffff;

			String ack = fromServer.readLine();
			if (ack == null)
				throw new TransmitterDeviceException("No Ack Response for Sync");
			if (!ack.equals("+"))
				throw new TransmitterDeviceException("Received \"" + ack + "\" instead of Ack for Sync");

			// parse response
			Pattern ms2 = Pattern.compile("(\\d):(\\w+):(\\w+)");
			Matcher ms2_match = ms2.matcher(resp);

			if (!ms2_match.matches()) {
				throw new TransmitterDeviceException("Wrong Sync Response: " + resp);
			}

			int msid = Integer.parseInt(ms2_match.group(1));
			String time_string_resp = ms2_match.group(2);
			String time_string_client = ms2_match.group(3);
			long time_long_client = Long.parseLong(time_string_client, 16);

			// ckeck correctness of message 2 response
			if (msid != PagingMessageType.SYNCREQUEST.getValue() || !time_string_server.equals(time_string_resp)) {
				throw new TransmitterDeviceException("Wrong Sync Response: " + resp);
			}

			// look for shortest RTT
			long rtt = time_rx - time_tx;
			if (rtt < time_rtt_min) {
				time_rtt_min = rtt;
				// my time when client received message - client time
				time_adjust = (time_tx + rtt / 2) - time_long_client;
			}
		}
		// 2.b) adapt client time
		if (Math.abs(time_adjust) > 65536)
			throw new TransmitterDeviceException("TimeDifference to large");
		String time_string_server = String.format("%04x", Math.abs(time_adjust));
		String sign = "+";
		if (time_adjust < 0) {
			sign = "-";
		}

		toServer.println(String.format("%s:%s%s", PagingMessageType.SYNCORDER.getValue(), sign, time_string_server));

		String ack = fromServer.readLine(); // +
		if (ack == null)
			throw new TransmitterDeviceException("No Ack Response for SyncResult");
		if (!ack.equals("+"))
			throw new TransmitterDeviceException("Received \"" + ack + "\" instead of Ack for SyncResult");

		// 3. Set Timeslots
		toServer.println(String.format("%s:%s", PagingMessageType.SLOTS.getValue(), transmitterDevice.getTimeSlot()));

		ack = fromServer.readLine(); // +
		if (ack == null)
			throw new TransmitterDeviceException("No Ack Response for TimeSlots");
		if (!ack.equals("+"))
			throw new TransmitterDeviceException("Received \"" + ack + "\" instead of Ack for TimeSlots");
	}

	@Override
	public void handleMessage(Message message, PrintWriter toServer, BufferedReader fromServer)
			throws TransmitterDeviceException, IOException, InterruptedException {
		int sequenceNumber = incrementSequenceNumber();
		// Mostly adapted from Sven Jung
		// See Diplomarbeit Jansen Page 30
		TransmitterDeviceProtocol.PagingMessageType type = null;
		switch (message.getFunctionalBits()) {
		case ACTIVATION:
			type = PagingMessageType.ALPHANUM;
			break; // todo check whether correct
		case ALPHANUM:
			type = PagingMessageType.ALPHANUM;
			break;
		case NUMERIC:
			type = PagingMessageType.NUMERIC;
			break;
		case TONE:
			type = PagingMessageType.ALPHANUM;
			break; // todo check whether correct
		}

		String msg = String.format("#%02X %s:%X:%X:%s:%s", sequenceNumber, type.getValue(), settings.getSendSpeed(),
				message.getAddress(), message.getFunctionalBits().getValue(), message.getText());
		toServer.println(msg);

		// Wait for ack
		String resp = fromServer.readLine();
		if (resp == null)
			throw new TransmitterDeviceException("No Messageack");

		Pattern ackPattern = Pattern.compile("#(\\p{XDigit}+) (\\+)"); // #04 +
		Matcher ms_match = ackPattern.matcher(resp);

		if (!ms_match.matches()) {
			throw new TransmitterDeviceException("Received wrong Messageack: " + resp);
		}

		// Check Seg Number
		int seq = Integer.parseInt(ms_match.group(1), 16);
		String ack = ms_match.group(2);

		if (!ack.equals("+") || !(seq == sequenceNumber + 1)) {
			throw new TransmitterDeviceException("Received wrong Messageack with wrong SequenceNumber: " + resp);
		}
	}

}
