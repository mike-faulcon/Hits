// Hits.java

import  javax.sound.midi.*;
import  java.awt.*;
import  java.awt.event.*;
import  javax.swing.*;
import  javax.swing.event.*;
import  java.io.*; 
//import java.util.ArrayList;
import java.util.Vector;

public class Hits implements ActionListener,ChangeListener
{  
  //  ********   GLOBAL VARIABLES   **********    
	int            resolution = 1;
	float          tempo;
	boolean        playing;
	int            position;    // current position of sequence
	int            start,end,length,offset;
	int            rows,cols;
	int[][]        beat;
	int[]		       instruments,notes;
	boolean        loading_from_file;
	//  *********    CONSTANTS    **************
	static final int   TICK_BUTTON_SIZE = 20;
	static final boolean  PRINT_INSTRUMENTS_ON_STARTUP = false;
	static final boolean  CONFIRM_CLOSE = false;
	//  *********    MIDI TOOLS   **************
	Sequencer      sequencer;    
	Sequence       mySequence;
	Track          myTrack;  
	Instrument[]   instrumentArray;
	//  *********  GUI COMPONENTS  *************
	JFrame             Composer = new JFrame();
	JToggleButton      play;     
	JButton			       save,load;
	JToggleButton[][]  hits;
	JToggleButton      tick;	
	Timer              timer;
	JScrollBar         speedy;
	JTextArea          what_speed;
	JTextField		     file_field;
	JSlider[]		       note_bars;
	JTextField[]       note_info;
	JComboBox[]        instrument_info;
	JToggleButton[]    muter;
  // ***********  CONSTRUCTOR  *******************
  public Hits() {
    Composer.getContentPane().setLayout(null);
    Composer.setSize(800,500);
		play = new JToggleButton("Play");
		play.setBounds(50,50,75,40);
    play.addActionListener(this);    
    save = new JButton("Save");
    save.setBounds(150,150,75,40);
    save.addActionListener(this);
		load = new JButton("Load");
		load.setBounds(250,150,75,40);
		load.addActionListener(this);
    rows = 8;
    cols = 16;
    beat = new int[rows][cols];
		instruments = new int[rows];
    notes = new int[rows];
    note_bars = new JSlider[rows];
    note_info = new JTextField[rows];
		instrument_info = new JComboBox[rows];
		muter = new JToggleButton[rows];
		
		instrumentArray = GetAvailableInstruments();	

		// Create Input List for Combobox Model
		Vector comboBoxItems = new Vector();
		for (int i = 0; i < instrumentArray.length; i++) {
			comboBoxItems.add( instrumentArray[i].getName() );
		}	

		loading_from_file = false;
		hits = new JToggleButton[rows][cols];
	  for ( int r = 0; r < rows; r++ ) {
		  for ( int c = 0; c < cols; c++ ) {
			  hits[r][c] = new JToggleButton();
			  hits[r][c].setBounds(50+c*TICK_BUTTON_SIZE,220+r*TICK_BUTTON_SIZE,TICK_BUTTON_SIZE,TICK_BUTTON_SIZE);
			  hits[r][c].addActionListener(this);
			  Composer.getContentPane().add( hits[r][c] );
		  }

		  note_bars[r] = new JSlider(30,90);
		  note_bars[r].setBounds(195+cols*TICK_BUTTON_SIZE,220+r*TICK_BUTTON_SIZE,100,TICK_BUTTON_SIZE);
		  note_bars[r].addChangeListener(this);		  
		  Composer.getContentPane().add( note_bars[r] );
		  
		  note_info[r] = new JTextField();
		  note_info[r].setBounds(295+cols*TICK_BUTTON_SIZE,220+r*TICK_BUTTON_SIZE,30,TICK_BUTTON_SIZE);
		  note_info[r].setText( "" + note_bars[r].getValue() );
			Composer.getContentPane().add( note_info[r] );		  
		  
		  muter[r] = new JToggleButton();
		  muter[r].setBounds(TICK_BUTTON_SIZE,220+r*TICK_BUTTON_SIZE,TICK_BUTTON_SIZE,TICK_BUTTON_SIZE);
		  muter[r].setSelected(true);
		  Composer.getContentPane().add( muter[r] );

		  instrument_info[r] = new JComboBox();
		  instrument_info[r].setBounds(75+cols*TICK_BUTTON_SIZE,220+r*TICK_BUTTON_SIZE,100,TICK_BUTTON_SIZE);
		  instrument_info[r].setModel( new DefaultComboBoxModel( comboBoxItems ) );
			Composer.getContentPane().add( instrument_info[r] );		 
		}

    tick = new JToggleButton();    
    tick.setBounds(50,400,TICK_BUTTON_SIZE,TICK_BUTTON_SIZE);
    tick.addActionListener(this);
		speedy = new JScrollBar(JScrollBar.HORIZONTAL);
		speedy.setBounds(270,100,100,25);
		speedy.setValue(4);
		speedy.setMinimum(2);
		speedy.setMaximum(8+10);    
		what_speed = new JTextArea(1,2);
		what_speed.setFont( new Font("my_bold",Font.BOLD,18) );
		what_speed.setBounds(235,100,25,25);
    file_field = new JTextField();
    file_field.setBounds(350,150,100,TICK_BUTTON_SIZE);		
    //*************  PRIMARY FRAME  **************
    Composer.getContentPane().add(play);        
		Composer.getContentPane().add(save);
    Composer.getContentPane().add(load);
    Composer.getContentPane().add(tick);
		Composer.getContentPane().add(speedy);
		Composer.getContentPane().add(what_speed);
    Composer.getContentPane().add(file_field);    
		Composer.setVisible(true);
    //***********************************************
		offset = 1;
		start = 0 + offset;
		end = 16 + offset;
		length = end - start;
		
    try {
	  	timer = new Timer();	

      sequencer = MidiSystem.getSequencer();
      sequencer.open();	  
      
			Reset();
			timer.start();
	  
    }  // end try
    catch ( MidiUnavailableException mue ) {}

		Composer.addWindowListener(new java.awt.event.WindowAdapter() {
			@Override
			public void windowClosing(java.awt.event.WindowEvent windowEvent) {

				if (CONFIRM_CLOSE) {
					if (JOptionPane.showConfirmDialog(Composer, 
						"Are you sure you want to Exit?", "Really Close?", 
						JOptionPane.YES_NO_OPTION,
						JOptionPane.QUESTION_MESSAGE) == JOptionPane.YES_OPTION){
						System.exit(0);
					}
				}
				else {
					System.exit(0);
				}
			}
		});
        
  } // end constructor

  public static void main(String[] args) {
		// Hits music = new Hits();
		new Hits();
  } // end main

  private void Update_Instruments() {
		if ( loading_from_file )
			return;
		
	  for ( int i = 0; i < instruments.length; i++ ) {
		  instruments[i] = instrument_info[i].getSelectedIndex();
		  notes[i] = note_bars[i].getValue();
	  }
  }

  private void Make_Music() throws InvalidMidiDataException {
		mySequence = new Sequence(Sequence.PPQ,resolution);	
		myTrack = mySequence.createTrack();

		// setup for instruments
		Update_Instruments();
    for ( int i = 0; i < instruments.length; i++ ) {
			Add_Event(ShortMessage.PROGRAM_CHANGE,myTrack,i,instruments[i],1,0);
			//Add_Event(ShortMessage.PROGRAM_CHANGE,myTrack,i,instruments[i],0,0);
		}

	  for ( int r = 0; r < rows; r++ ) {
		  for ( int c = 0; c < cols; c++ ) {
			  if ( hits[r][c].isSelected() ) {
				  beat[r][c] = 1;
				  if ( muter[r].isSelected() ) {
					  Add_Event(ShortMessage.NOTE_ON,myTrack,r,notes[r],100,c+offset);
					  Add_Event(ShortMessage.NOTE_OFF,myTrack,r,notes[r],100,c+offset+1);
				  }
			  }
			  else {
				  beat[r][c] = 0;
			  }		
		  }
	  }

    Add_Event(ShortMessage.NOTE_ON,myTrack,0,65,100,2000); // end of sequence
    sequencer.setSequence(mySequence);
		sequencer.setTempoFactor(tempo);

		// these looping functions require java version 1.5
		sequencer.setLoopCount(Sequencer.LOOP_CONTINUOUSLY);
		sequencer.setLoopStartPoint(start); // 0,1
		sequencer.setLoopEndPoint(end);     // 16,17
  }

  private static void Add_Event(int type, Track tr, int chan, int note_instrument, int vol, long when) {
    try {        
			// note_instrument --> NOTE_ON:note, PROGRAM_CHANGE:instrument
			ShortMessage msg = new ShortMessage();        
	    msg.setMessage(type,chan,note_instrument,vol);
			tr.add( new MidiEvent(msg,when) );
		} catch ( InvalidMidiDataException imde ) { imde.printStackTrace(); }
  }

  // private static void Add_Note(Track track, int channel, int startTick, int tickLength, int key, int velocity) {
	// 	try {

	// 		ShortMessage on = new ShortMessage();
	// 		on.setMessage(ShortMessage.NOTE_ON, channel, key, velocity);
	// 		track.add(new MidiEvent(on, startTick));

	// 		ShortMessage off = new ShortMessage();
	// 		off.setMessage(ShortMessage.NOTE_OFF, channel, key, velocity);		
	// 		track.add(new MidiEvent(off, startTick + tickLength));

	// 	} catch ( InvalidMidiDataException imde ) { imde.printStackTrace(); }
  // }

  public void actionPerformed(ActionEvent action) {
      String command = action.getActionCommand();

	  if ( command == "Play" ) {		  
		  if ( playing ) {
			  sequencer.stop();
			  sequencer.setTickPosition(0);
			  playing = false;
			  play.setForeground(Color.black);
		  }
		  else {  
			  Reset();
			  sequencer.start();        
			  playing = true;
			  play.setForeground(Color.green);
		  }
	  } // end if button is "Play" button	 

	  else if ( command == "Save" ) {		  
		  Reset();
		  Save( file_field.getText() );
	  }

	  else if ( command == "Load" ) {
		  Load( file_field.getText() );
		  Reset();
	  }
  }

	public void stateChanged(ChangeEvent e) {
		System.out.println("stateChanged");
		// JSlider source = (JSlider)e.getSource();

		// for ( int n = 0; n < instruments.length; n++ ) {
		// 	if ( source == note_bars[n] ) {
		// 		note_info[n].setText(String.valueOf( source.getValue() ));
		// 		Play_Note(n);
		// 		return;
		// 	}
		// }		
	}

	// private void Play_Note(int instrumentIndex) {}

	void Reset() {
		sequencer.stop();
		playing = false;
		play.setSelected(false);
		play.setForeground(Color.black);
		try {  Make_Music();  }
		catch (InvalidMidiDataException e) {}
    sequencer.setTickPosition(0);
		loading_from_file = false;
	}

	void Save(String beatName) {
		String beatFilePath = GetBeatFilePath(beatName);
		if ( beatFilePath == "" ) {
			return;
		}

		try {
			FileOutputStream saver = new FileOutputStream(beatFilePath);			

			for ( int r = 0; r < rows; r++ ) 
				for ( int c = 0; c < cols; c++ )
					saver.write( beat[r][c] );
			
			for ( int i = 0; i < instruments.length; i++ ) {
				saver.write( instruments[i] );
				saver.write( notes[i] );
			}

			saver.close();
		}
		catch (IOException ioe) { ioe.printStackTrace(); }
	}

	void Load(String beatName) {
		String beatFilePath = GetBeatFilePath(beatName);
		if ( beatFilePath == "" ) {
			return;
		}

		loading_from_file = true;

		try {
			FileInputStream loader = new FileInputStream(beatFilePath);

			for ( int r = 0; r < rows; r++ ) {
				for ( int c = 0; c < cols; c++ ) {
					beat[r][c] = loader.read();
					hits[r][c].setSelected( beat[r][c] != 0 );
				}
			}

			for ( int i = 0; i < instruments.length; i++ ) {
				int instrumentIndex = loader.read();
				instruments[i] = instrumentIndex;
				//instrument_info[i].setSelectedIndex( instrumentIndex );
				String instrumentName = GetInstrumentName( instrumentIndex );
				System.out.println("Instrument [" + instrumentIndex + "] name: " + instrumentName );
				instrument_info[i].setSelectedItem( instrumentName );
				
				notes[i] = loader.read();
				note_bars[i].setValue( notes[i] );
				
				muter[i].setSelected(true);
			}

			loader.close();
		}
		catch (FileNotFoundException fe) { fe.printStackTrace(); }
		catch (IOException ioe) { ioe.printStackTrace(); }
	}

	String GetBeatFilePath(String beatName) {
		if ( beatName == "" )
			return "";

	  String beatFileName = beatName + ".btf";
		String path = "./beats/" + beatFileName;
		System.out.println("Beat File Path: " + path);

		return path;
	}

	Instrument[] GetAvailableInstruments() {
		try {
			Synthesizer synth = MidiSystem.getSynthesizer();
			synth.open();
			
			// Get instruments
			Instrument[] instrumentList = synth.getAvailableInstruments();

			synth.close();

			return instrumentList;
		}  // end try
		catch ( MidiUnavailableException mue ) {}
		catch ( ArrayIndexOutOfBoundsException ae ) {}
		
		//return null;
		return new Instrument[0];
	}

	String GetInstrumentName(int index) {
		return index < instrumentArray.length ? instrumentArray[index].getName() : "";
	}
  
	//*********************************************
  
  class Timer extends Thread {
	  public void run() {		  
			while(true) {
				try {
					sleep(60);	// almost 1/16 of a second
				}   
				catch (InterruptedException ie) { ie.printStackTrace(); }
		
				position = (int) sequencer.getTickPosition() - offset;
				tick.setBounds(50+position*20,400,20,20);

				// This part should only change when the corresponding ui control is updated.
				// ToDo
				tempo = resolution * (float)speedy.getValue() / (float)2.0;
				what_speed.setText("" + tempo);		    		    
			}
	  }
  }	

  //*******************************************
  
} // end Hits class