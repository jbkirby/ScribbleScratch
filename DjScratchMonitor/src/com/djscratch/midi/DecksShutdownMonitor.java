package com.djscratch.midi;


import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.MidiDevice;
import javax.sound.midi.MidiMessage;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.MidiUnavailableException;
import javax.sound.midi.Receiver;
import javax.sound.midi.Sequencer;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Synthesizer;
import javax.sound.midi.Transmitter;
import javax.sound.midi.MidiDevice.Info;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.Timer;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.ListSelectionListener;

public class DecksShutdownMonitor extends JFrame {
	private static final long serialVersionUID = 1L;
	private static final int DEFAULT_MS_UNTIL_STOP = 30000;

	private static final int LEFT_NS7_PLAY_NOTE = 17;
	private static final int RIGHT_NS7_PLAY_NOTE = 50;
	private static final int LEFT_SERATO_PLAY_NOTE = 60;
	private static final int RIGHT_SERATO_PLAY_NOTE = 61;

	private static final int NS7_DECK_POSITION_FIRST_BYTE = 176;
	private static final int LEFT_NS7_DECK_TIMESTAMP_FIRST_BYTE = 224;
	private static final int LEFT_NS7_DECK_POSITION_SECOND_BYTE = 0;
	private static final int RIGHT_NS7_DECK_TIMESTAMP_FIRST_BYTE = 226;
	private static final int RIGHT_NS7_DECK_POSITION_SECOND_BYTE = 2;
	private static final int VELOCITY_HISTORY_ENTRIES = 10;
	private static final float VELOCITY_DEVIATION_FOR_MANIPULATION = 0.005f;

	private static final int MAX_POSITION = 128;
	private static final int MAX_TIMESTAMP = 16383;

	private JTextArea outputTextArea;

	private MidiDevice midiOutDevice;
	private Receiver midiOutReceiver;

	private MidiDevice midiInDevice;
	private Transmitter midiInTransmitter;

	private boolean leftDeckPlaying = false;
	private boolean rightDeckPlaying = false;

	private final ShortMessage stopLeftDeckMsg;
	private final ShortMessage stopRightDeckMsg;

	private int msUntilStop;

	private Timer stopTimer;

	private int leftDeckLastTimeStamp;
	private int leftDeckLastPosition;
	private int leftDeckPositionDelta;

	private int rightDeckLastTimeStamp;
	private int rightDeckLastPosition;
	private int rightDeckPositionDelta;

	private LinkedList<Float> leftDeckVelocityHistory;
	private float leftDeckVelocityAverage;
	private LinkedList<Float> rightDeckVelocityHistory;
	private float rightDeckVelocityAverage;

	public DecksShutdownMonitor() {
		this.getContentPane().add(constructMainGuiPanel());

		this.setDefaultCloseOperation(DISPOSE_ON_CLOSE);
		this.setTitle("NS7 Activity Monitor");

		this.msUntilStop = DEFAULT_MS_UNTIL_STOP;

		this.leftDeckLastTimeStamp = -1;
		this.leftDeckLastPosition = -1;
		this.rightDeckLastTimeStamp = -1;
		this.rightDeckLastPosition = -1;
		this.leftDeckPositionDelta = 0;
		this.rightDeckPositionDelta = 0;

		this.leftDeckVelocityHistory = new LinkedList<Float>();
		this.rightDeckVelocityHistory = new LinkedList<Float>();
		
		for(int i = 0; i < VELOCITY_HISTORY_ENTRIES; i++) {
			leftDeckVelocityHistory.addLast(new Float(0.0));
			rightDeckVelocityHistory.addLast(new Float(0.0));
		}

		this.leftDeckVelocityAverage = 0.0f;
		this.rightDeckVelocityAverage = 0.0f;

		stopLeftDeckMsg = new ShortMessage();
		stopRightDeckMsg = new ShortMessage();

		try {
			stopLeftDeckMsg.setMessage(ShortMessage.NOTE_ON, 0, LEFT_SERATO_PLAY_NOTE, 127);
			stopRightDeckMsg.setMessage(ShortMessage.NOTE_ON, 0, RIGHT_SERATO_PLAY_NOTE, 127);
		} catch(InvalidMidiDataException ex) {
			ex.printStackTrace();
		}
		
		// Populate the lists
		setMidiDevices();
		startTimer();
	}

	private JPanel constructMainGuiPanel() {
		// Initialize the panel
		JPanel retPanel = new JPanel();
		retPanel.setLayout(new BoxLayout(retPanel, BoxLayout.Y_AXIS));

		outputTextArea = new JTextArea("");
		outputTextArea.setEditable(false);
		
		// Quit button
		JButton quitButton = new JButton("Quit");
		quitButton.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {				
				shutdown();
			}			
		});

		JPanel buttonPanel = new JPanel();
		buttonPanel.setLayout(new BoxLayout(buttonPanel, BoxLayout.X_AXIS));		
		buttonPanel.add(Box.createHorizontalGlue());
		buttonPanel.add(quitButton);

		retPanel.add(new JScrollPane(outputTextArea));
		retPanel.add(Box.createRigidArea(new Dimension(1, 10)));
		retPanel.add(buttonPanel);
		
		return retPanel;
	}
	
	private void startTimer() {
		if(midiInTransmitter != null && midiOutReceiver != null) {
			if(stopTimer == null) {
				stopTimer = new Timer(msUntilStop, new ActionListener() {
					@Override
					public void actionPerformed(ActionEvent e) {
						if(leftDeckPlaying) {
							outputTextArea.append("Stopping left deck.\n");
							sendMidiToOutputPort(stopLeftDeckMsg);
							leftDeckPlaying = false;
						}

						if(rightDeckPlaying) {
							outputTextArea.append("Stopping right deck.\n");
							sendMidiToOutputPort(stopRightDeckMsg);
							rightDeckPlaying = false;
						}
					}
				});
			}
			
			if(stopTimer.isRunning())
				stopTimer.restart();
			else {
				stopTimer.start();
			}
		} else {
			outputTextArea.append("Cannot start timer: MIDI input " + (midiInTransmitter == null ? "is null" : "is valid") + 
					"; MIDI output "+ (midiOutReceiver == null ? "is null." : "is valid."));
		}
	}

	protected void setMidiDevices() {
		outputTextArea.append("Attempting to open MIDI I/O ports...\n");
		// Query the system for available ports.
		MidiDevice device;
		Info[] midiDevInfo = MidiSystem.getMidiDeviceInfo();

		for (Info info : midiDevInfo) {
			try {
				device = MidiSystem.getMidiDevice(info);
			} catch (MidiUnavailableException e) {
				outputTextArea.append("Couldn't open MIDI device " + info.getName() + "\n");
				continue;
			}

			if (!(device instanceof Sequencer) && !(device instanceof Synthesizer)) {
				if (device.getMaxReceivers() == -1 || device.getMaxReceivers() > 0) {
					if(device.getDeviceInfo().getDescription().toLowerCase().contains("serato")) {
						setMidiOutputDevice(device);
					}
				}

				if (device.getMaxTransmitters() == -1 || device.getMaxTransmitters() > 0) {
					if(device.getDeviceInfo().getDescription().toLowerCase().contains("ns7")) {
						setMidiInputDevice(device);
					}
				}
			}
		}
	}
	
	private void setMidiOutputDevice(MidiDevice device) {
		// Close existing ports / devices
		if (midiOutReceiver != null)
			midiOutReceiver.close();
		if (midiOutDevice != null)
			midiOutDevice.close();

		// We'll attempt to open the port before actually assigning it to our global variable-
		// this will ensure that the port and receiver global vars are always in sync.

		// Open the selected device.
		if (!(device.isOpen())) {
			try {
				device.open();

				midiOutDevice = device;
				midiOutReceiver = midiOutDevice.getReceiver();
				
			} catch(MidiUnavailableException ex) {
				outputTextArea.append("Couldn't open MIDI device for output: " + midiOutDevice.getDeviceInfo().getDescription() + "\n");
				return;
			}

			outputTextArea.append("Successfully opened MIDI device for output: " + midiOutDevice.getDeviceInfo().getDescription() + "\n");
		}
	}
	
	private void setMidiInputDevice(MidiDevice device) {
		// Close existing ports / devices
		if (midiInTransmitter != null) {
			midiInTransmitter.setReceiver(null);
			midiInTransmitter.close();
		}
		if (midiInDevice != null)
			midiInDevice.close();

		if (!(device.isOpen())) {
			try {
				midiInDevice = device;
				midiInTransmitter = midiInDevice.getTransmitter();

				// Set up a new receiver to handle MIDI messages coming in off the input port.
				midiInTransmitter.setReceiver(new Receiver() {
					@Override
					public void close() {
						outputTextArea.append("Server: MIDI input port received close request.");
					}

					@Override
					public void send(MidiMessage message, long timeStamp) {
						midiReceived(message, timeStamp);
					}
				});

				midiInDevice.open();
				
				outputTextArea.append("Output device set to " + midiOutDevice.getDeviceInfo().getName() + "\n");
			} catch(MidiUnavailableException ex) {
				outputTextArea.append("Couldn't open MIDI device for input: " + midiInDevice.getDeviceInfo().getDescription());
				return;
			}

			outputTextArea.append("Successfully opened MIDI device for input: " + midiInDevice.getDeviceInfo().getDescription());
		}
	}

	/**
	 * Sends the parameter MidiMessage to the currently selected MIDI output port.
	 * @param midiMsg
	 * @return
	 */
	public synchronized boolean sendMidiToOutputPort(MidiMessage midiMsg) {
		outputTextArea.append("Sending MIDI to output device.\n");

		if (midiOutReceiver != null && midiMsg != null) {
			try {
				midiOutReceiver.send(midiMsg, -1);
			} catch (IllegalStateException ex) {
				outputTextArea.append("Could not send MIDI data to output device receiver: " + ex.getMessage() + "\n");
				return false;
			}

			return true;
		}

		return false;
	}

	/**
	 * Exit the MidiServer application.
	 */
	private void shutdown() {
		// Dispose of the window first, as unregistering jmdns can be somewhat time consuming,
		// and we want to maintain a responsive feel.
		DecksShutdownMonitor.this.dispose();

		System.exit(0);
	}

	private void restartTimer() {
		if(stopTimer != null) {
			outputTextArea.append("Restarting timer.\n");
			stopTimer.restart();
		}
	}

	private void midiReceived(MidiMessage message, long midiTimeStamp) {
		
		if(message instanceof ShortMessage) {
			int b1 = message.getStatus();
			int b2 = message.getMessage()[1];
			int b3 = message.getMessage()[2];

//			outputTextArea.append(b1 + " " + b2 + " " + b3);
			
			if(b1 == ShortMessage.NOTE_ON) {
				if(b2 == LEFT_NS7_PLAY_NOTE && b3 == 127) {
					leftDeckPlaying = !leftDeckPlaying;
					outputTextArea.append("Left deck " + (leftDeckPlaying ? "playing." : "stopped") + "\n");
				} else if(b2 == RIGHT_NS7_PLAY_NOTE && b3 == 127) {
					rightDeckPlaying = !rightDeckPlaying;
					outputTextArea.append("Right deck " + (rightDeckPlaying ? "playing." : "stopped") + "\n");
				}

				restartTimer();

			} else if(b1 == NS7_DECK_POSITION_FIRST_BYTE) {
				if(b2 == LEFT_NS7_DECK_POSITION_SECOND_BYTE) {
					leftDeckPositionDelta = (b3 - leftDeckLastPosition);

					// NOTE this is incorrect for non-steady velocity, e.g. scratching. That's OK for our
					// purposes here, but NOT OK for actually calculating instantaneous velocity.
					if(Math.abs(leftDeckPositionDelta) > 4) {
						// We wrapped around
						if(leftDeckLastPosition < b3) {
							leftDeckPositionDelta = -MAX_POSITION - leftDeckLastPosition + b3;
						} else {
							leftDeckPositionDelta = MAX_POSITION - leftDeckLastPosition + b3;
						}
					}

					leftDeckLastPosition = b3;
				} else if(b2 == RIGHT_NS7_DECK_POSITION_SECOND_BYTE) {
					rightDeckPositionDelta = (b3 - rightDeckLastPosition);

					// NOTE this is incorrect for non-steady velocity, e.g. scratching. That's OK for our
					// purposes here, but NOT OK for actually calculating instantaneous velocity.
					if(Math.abs(rightDeckPositionDelta) > 4) {
						// We wrapped around
						if(rightDeckLastPosition < b3) {
							rightDeckPositionDelta = -MAX_POSITION - rightDeckLastPosition + b3;
						} else {
							rightDeckPositionDelta = MAX_POSITION - rightDeckLastPosition + b3;
						}
					}

					rightDeckLastPosition = b3;
				} else {
					restartTimer();
				}
			} else if(b1 == LEFT_NS7_DECK_TIMESTAMP_FIRST_BYTE) {
				int timestamp = (b3 << 7) | b2;

				if(leftDeckLastTimeStamp < 0) {
					leftDeckLastTimeStamp = timestamp;
				} else {
					int deltaT = 0;
					if(timestamp < leftDeckLastTimeStamp) {
						// Timestamp wrapped.
						deltaT = MAX_TIMESTAMP - leftDeckLastTimeStamp + timestamp; 
					} else {
						deltaT = timestamp - leftDeckLastTimeStamp;
					}

					leftDeckLastTimeStamp = timestamp;

					float velocity = ((float)leftDeckPositionDelta / (float)deltaT) * 1000.0f;
					leftDeckVelocityAverage = addVelocityAndCalculateAverage(leftDeckVelocityHistory, leftDeckVelocityAverage, velocity);
					float velDifferential = Math.abs(velocity) - Math.abs(leftDeckVelocityAverage);
					if(velDifferential > (VELOCITY_DEVIATION_FOR_MANIPULATION * Math.abs(leftDeckVelocityAverage))) {
//						outputTextArea.append("Manipulation detected: " + velDifferential);
						restartTimer();
						
//						System.out.print("Position delta: " + leftDeckPositionDelta + /*"\tTimestamp:" + timestamp +*/ ".\tdeltaT: " + deltaT + ".\t");
//						System.out.print("Left deck velocity: "); System.out.format("%.3f%n", velocity);
//						outputTextArea.append("\tAverage Velocity: " + leftDeckVelocityAverage);
					}

				}

			} else if(b1 == RIGHT_NS7_DECK_TIMESTAMP_FIRST_BYTE) {
				int timestamp = (b3 << 7) | b2;

				if(rightDeckLastTimeStamp < 0) {
					rightDeckLastTimeStamp = timestamp;
				} else {
					int deltaT = 0;
					if(timestamp < rightDeckLastTimeStamp) {
						// Timestamp wrapped.
						deltaT = MAX_TIMESTAMP - rightDeckLastTimeStamp + timestamp; 
					} else {
						deltaT = timestamp - rightDeckLastTimeStamp;
					}

					rightDeckLastTimeStamp = timestamp;

					float velocity = ((float)rightDeckPositionDelta / (float)deltaT) * 1000.0f;
					rightDeckVelocityAverage = addVelocityAndCalculateAverage(rightDeckVelocityHistory, rightDeckVelocityAverage, velocity);
					float velDifferential = Math.abs(velocity) - Math.abs(rightDeckVelocityAverage);
					if(velDifferential > (VELOCITY_DEVIATION_FOR_MANIPULATION * Math.abs(rightDeckVelocityAverage))) {
//						outputTextArea.append("Manipulation detected: " + velDifferential);
						restartTimer();
					}
				}

			} else {
				// For any other message, restart the timer.
				restartTimer();
			}
		}
	}

	private float addVelocityAndCalculateAverage(LinkedList<Float> history, float average, float latestVel) {
		float firstVal = history.removeFirst().floatValue();
		history.addLast(latestVel);

		return average - firstVal/(float)history.size() + latestVel/(float)history.size();
	}

	public static void main(String[] args) {
		DecksShutdownMonitor server = new DecksShutdownMonitor();

		server.pack();
		server.setVisible(true);
	}
}
