# Offline Speech Centric Personal Assistant App using Whisper & TFLite

hands-free, privacy focused personal assistant. All voice models are local on the phone and the llm for chat interface can be directed to your locally running at-home llm using standard API.  

There will be multiple tasks and associated commands suitable for a personal assistant (PA). Tasks and associated voice commands are modifiable (not hardcoded) to expand the usefullness of our PA.  

There are 3 cases where voice interaction is used:
1. app control (command router). Defined voice commands for application tasks  
2. chat interface with a llm. A task within the app which is the same as other chat interfaces for llms.  
   command string is user defined, default "enter chat mode"
3. General entries recording. Here an arbitrarily long voice entry is recorded. Once finished it is automatically transcribed in the background.   
   command string is user defined, default "new <type> entry", where <type> can be one of several options, ie. "personal", "project x" (x is from a list of projects), "inbox"


| input type      | note                                                                               |
| app controls    | real time, short, fast response                                                    |
| chat interface  | short to 5 minutes of input, immediate transcribe for llm prompt                   |
| entry recording | arbitrary long input, audio saved to file, transcribe lower priority in background |
