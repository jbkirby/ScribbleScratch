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
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.Timer;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.ListSelectionListener;

public class MidiServer extends JFrame {
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

	private JList midiOutPortsList;
	private JList midiInPortsList;

	private MidiDevice midiOutPort;
	private Receiver midiReceiver;

	private MidiDevice midiInPort;
	private Transmitter midiTransmitter;

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

	public MidiServer() {
		this.getContentPane().add(constructMainGuiPanel());

		this.setDefaultCloseOperation(DISPOSE_ON_CLOSE);
		this.setTitle("MIDI Server");

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
	}

	private JPanel constructMainGuiPanel() {
		// Initialize the panel
		JPanel retPanel = new JPanel();
		retPanel.setLayout(new BoxLayout(retPanel, BoxLayout.Y_AXIS));

		// Create the JList of MIDI output ports.
		midiOutPortsList = new JList();
		midiOutPortsList.addListSelectionListener(new MidiOutputDeviceListSelectionListener());
		midiOutPortsList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

		JScrollPane midiOutPortsListScroll = new JScrollPane(midiOutPortsList);
		midiOutPortsListScroll.setMinimumSize(new Dimension(300, 100));
		midiOutPortsListScroll.setBorder(BorderFactory.createTitledBorder("Send MIDI to:"));

		// Create the JList of MIDI input ports.
		midiInPortsList = new JList();
		midiInPortsList.addListSelectionListener(new MidiInputDeviceListSelectionListener());
		midiInPortsList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

		JScrollPane midiInPortsListScroll = new JScrollPane(midiInPortsList);
		midiInPortsListScroll.setMinimumSize(new Dimension(300, 100));
		midiInPortsListScroll.setBorder(BorderFactory.createTitledBorder("Listen for MIDI from:"));

		// Quit button
		JButton quitButton = new JButton("Quit");
		quitButton.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {				
				shutdown();
			}			
		});

		// Manual stop buttons
//		JButton stopLeftDeckButton = new JButton("Send Left Message");
//		stopLeftDeckButton.addActionListener(new ActionListener() {
//			@Override
//			public void actionPerformed(ActionEvent e) {				
//				sendMidiToOutputPort(stopLeftDeckMsg);
//			}			
//		});
//
//		JButton stopRightDeckButton = new JButton("Send Right Message");
//		stopRightDeckButton.addActionListener(new ActionListener() {
//			@Override
//			public void actionPerformed(ActionEvent e) {				
//				sendMidiToOutputPort(stopRightDeckMsg);
//			}			
//		});

		JPanel buttonPanel = new JPanel();
		buttonPanel.setLayout(new BoxLayout(buttonPanel, BoxLayout.X_AXIS));		

//		buttonPanel.add(stopLeftDeckButton);
//		buttonPanel.add(stopRightDeckButton);
		buttonPanel.add(Box.createHorizontalGlue());
		buttonPanel.add(quitButton);

		JButton startTimerButton = new JButton("Start Timer");
		startTimerButton.addActionListener(new ActionListener() {

			@Override
			public void actionPerformed(ActionEvent arg0) {
				startTimer();
			}

		});

		retPanel.add(midiOutPortsListScroll);
		retPanel.add(Box.createRigidArea(new Dimension(1, 5)));
		retPanel.add(midiInPortsListScroll);
		retPanel.add(Box.createRigidArea(new Dimension(1, 10)));
		retPanel.add(startTimerButton);
		retPanel.add(buttonPanel);

		// Populate the lists
		populateMidiPortsList();

//		midiOutPortsList.getSelectionModel().setSelectionInterval(0, 0);
//		midiInPortsList.getSelectionModel().setSelectionInterval(1, 1);

		startTimer();
		
		return retPanel;
	}
	
	private void startTimer() {
		if(midiTransmitter != null && midiReceiver != null) {
			if(stopTimer == null) {
				stopTimer = new Timer(msUntilStop, new ActionListener() {
					@Override
					public void actionPerformed(ActionEvent e) {
						if(leftDeckPlaying) {
							System.out.println("Stopping left deck.");
							sendMidiToOutputPort(stopLeftDeckMsg);
							leftDeckPlaying = false;
						}

						if(rightDeckPlaying) {
							System.out.println("Stopping right deck.");
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
			System.err.println("Cannot start timer until midi transmitter and receiver are selected!");
		}
	}

	protected void populateMidiPortsList() {
		// MIDI out list
		DefaultListModel midiOutputPortsListModel = new DefaultListModel();
		midiOutPortsList.setModel(midiOutputPortsListModel);
		midiOutPortsList.setCellRenderer(new MidiDeviceCellRenderer());

		// MIDI in list
		DefaultListModel midiInputPortsListModel = new DefaultListModel();
		midiInPortsList.setModel(midiInputPortsListModel);
		midiInPortsList.setCellRenderer(new MidiDeviceCellRenderer());

		// Query the system for available ports.
		MidiDevice device;
		Info[] midiDevInfo = MidiSystem.getMidiDeviceInfo();

		for (Info info : midiDevInfo) {
			try {
				device = MidiSystem.getMidiDevice(info);
			} catch (MidiUnavailableException e) {
				System.out.println("Server: Couldn't open MIDI device " + info.getName());
				continue;
			}

			if (!(device instanceof Sequencer) && !(device instanceof Synthesizer)) {
				if (device.getMaxReceivers() == -1 || device.getMaxReceivers() > 0) {
					midiOutputPortsListModel.addElement(device);
				}

				if (device.getMaxTransmitters() == -1 || device.getMaxTransmitters() > 0) {
					midiInputPortsListModel.addElement(device);
				}
			}
		}
	}

	/**
	 * Sends the parameter MidiMessage to the currently selected MIDI output port.
	 * @param midiMsg
	 * @return
	 */
	public synchronized boolean sendMidiToOutputPort(MidiMessage midiMsg) {
		System.out.println("Server: Sending MIDI to selected MIDI port.");

		if (midiReceiver != null && midiMsg != null) {
			try {
				midiReceiver.send(midiMsg, -1);
			} catch (IllegalStateException ex) {
				System.out.println("Server: could not send MIDI data to receiver -> " + ex.getMessage());
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
		MidiServer.this.dispose();

		System.exit(0);
	}

	/**
	 * A simple ListCellRenderer that uses a MidiDevice's Info object to retrieve a more
	 * readable display name for a MidiDevice.
	 *
	 */
	private class MidiDeviceCellRenderer extends JLabel implements ListCellRenderer {
		public MidiDeviceCellRenderer() {
			setOpaque(true);
		}

		public Component getListCellRendererComponent(JList list,
				Object value,
				int index,
				boolean isSelected,
				boolean cellHasFocus) {

			assert (value instanceof MidiDevice);

			Info deviceInfo = ((MidiDevice)value).getDeviceInfo();
			setText(deviceInfo.getName() + " : " + deviceInfo.getDescription());

			Color background;
			Color foreground;

			// check if this cell represents the current DnD drop location
			JList.DropLocation dropLocation = list.getDropLocation();
			if (dropLocation != null
					&& !dropLocation.isInsert()
					&& dropLocation.getIndex() == index) {

				background = Color.BLUE;
				foreground = Color.WHITE;

				// check if this cell is selected
			} else if (isSelected) {
				background = Color.BLUE;
				foreground = Color.WHITE;

				// unselected, and not the DnD drop location
			} else {
				background = Color.WHITE;
				foreground = Color.BLACK;
			};

			setBackground(background);
			setForeground(foreground);

			return this;
		}
	}

	/**
	 * Manages the opening and closing of MIDI ports selected by the user.
	 */
	private class MidiOutputDeviceListSelectionListener implements ListSelectionListener {
		@Override
		public void valueChanged(ListSelectionEvent e) {
			if (!e.getValueIsAdjusting()) {
				if (e.getFirstIndex() != -1) {

					// Close existing ports / devices
					if (midiReceiver != null)
						midiReceiver.close();
					if (midiOutPort != null)
						midiOutPort.close();

					// We'll attempt to open the port before actually assigning it to our global variable-
					// this will ensure that the port and receiver global vars are always in sync.
					MidiDevice tempMidiOutPort = (MidiDevice)midiOutPortsList.getSelectedValue();

					// Open the selected device.
					if (!(tempMidiOutPort.isOpen())) {
						try {
							tempMidiOutPort.open();

							midiOutPort = tempMidiOutPort;
							midiReceiver = midiOutPort.getReceiver();
						} catch(MidiUnavailableException ex) {
							System.err.println("Server: Couldn't open MIDI device for output: " + midiOutPort.getDeviceInfo().getDescription());
							return;
						}

						System.out.println("Server: Successfully opened MIDI device for output: " + midiOutPort.getDeviceInfo().getDescription());
					}		
				}
			}	
		}	
	}

	/**
	 * Manages the opening and closing of MIDI ports selected by the user.
	 *
	 */
	private class MidiInputDeviceListSelectionListener implements ListSelectionListener {
		@Override
		public void valueChanged(ListSelectionEvent e) {
			if (!e.getValueIsAdjusting()) {
				if (e.getFirstIndex() != -1) {

					System.out.print("\tClosing existing MIDI input devices...");
					// Close existing ports / devices
					if (midiTransmitter != null) {
						midiTransmitter.setReceiver(null);
						midiTransmitter.close();
					}
					if (midiInPort != null)
						midiInPort.close();

					System.out.println("\tdone.");

					// We'll attempt to open the port before actually assigning it to our global variable-
					// this will ensure that the port and transmitter global vars are always in sync.
					MidiDevice tempMidiInPort = (MidiDevice)midiInPortsList.getSelectedValue();

					// Open the selected device.
					if (!(tempMidiInPort.isOpen())) {
						try {
							midiInPort = tempMidiInPort;
							midiTransmitter = midiInPort.getTransmitter();

							// Set up a new receiver to handle MIDI messages coming in off the input port.
							midiTransmitter.setReceiver(new Receiver() {
								@Override
								public void close() {
									System.out.println("Server: MIDI input port received close request.");
								}

								@Override
								public void send(MidiMessage message, long timeStamp) {
									midiReceived(message, timeStamp);
								}
							});

							midiInPort.open();
						} catch(MidiUnavailableException ex) {
							System.err.println("Server: Couldn't open MIDI device for input: " + midiInPort.getDeviceInfo().getDescription());
							return;
						}

						System.out.println("Server: Successfully opened MIDI device for input: " + midiInPort.getDeviceInfo().getDescription());
					}		
				}
			}	
		}	
	}

	private void restartTimer() {
		if(stopTimer != null) {
			//System.out.println("Restarting timer.");
			stopTimer.restart();
		}
	}

	private void midiReceived(MidiMessage message, long midiTimeStamp) {
		
		if(message instanceof ShortMessage) {
			int b1 = message.getStatus();
			int b2 = message.getMessage()[1];
			int b3 = message.getMessage()[2];

//			System.out.println(b1 + " " + b2 + " " + b3);
			
			if(b1 == ShortMessage.NOTE_ON) {
				if(b2 == LEFT_NS7_PLAY_NOTE && b3 == 127) {
					leftDeckPlaying = !leftDeckPlaying;
					System.out.println("Left deck " + (leftDeckPlaying ? "playing." : "stopped"));
				} else if(b2 == RIGHT_NS7_PLAY_NOTE && b3 == 127) {
					rightDeckPlaying = !rightDeckPlaying;
					System.out.println("Right deck " + (rightDeckPlaying ? "playing." : "stopped"));
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
//						System.out.println("Manipulation detected: " + velDifferential);
						restartTimer();
						
//						System.out.print("Position delta: " + leftDeckPositionDelta + /*"\tTimestamp:" + timestamp +*/ ".\tdeltaT: " + deltaT + ".\t");
//						System.out.print("Left deck velocity: "); System.out.format("%.3f%n", velocity);
//						System.out.println("\tAverage Velocity: " + leftDeckVelocityAverage);
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
//						System.out.println("Manipulation detected: " + velDifferential);
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
		MidiServer server = new MidiServer();

		server.pack();
		server.setVisible(true);
	}
}
