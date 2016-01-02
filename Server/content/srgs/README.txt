Javascript implementation of an SRGS grammar processor

1) <a href='grxml2js.xq'/> is an XQuery script that converts an SRGS xml file
to javascript code that builds a Grammar object. The <a href='grxml2js'> script
runs the Saxon XQuery processor from the command line and performs the
conversion. Pass the input grxml file as the first argument and the output js
file as the second argument, e.g

   ./grxml2js spotify.grxml spotify.grxml.js

2) The <a href='parse'/> script runs the Rhino javascript engine from the command line. Pass the generated grammar file from (1) as the argument. Then type
in input at the console. When you hit return it will try to parse the line
you typed in. If succesful it will print the SRGS "out" json. For example:

    echo "Spotify play something by Joe Satriani" | ./parse play-genre.grxml.

{Artist:joe satriani;Dictation:false;Type:artist;Command:Query;Paused:false;Query:artist=joe%20satriani;DecodedQuery:artist=joe satriani;}

