load("chartparser.js");
load("srgs.js");
load(arguments[0]);
grammar.$check();
//var input = "Nigel can I hear the next track"
var reader = new java.io.BufferedReader(new java.io.InputStreamReader(java.lang.System.in));
while (true) {
    var input = reader.readLine();
    if (input == null) break;
    var startTime = new Date();
    print("grammar root="+grammar.$root);
    var parseChart = parseString(input, grammar, grammar.$root, undefined);
    var parseTime = new Date() - startTime;
    print("parse time: "+parseTime +"ms");
    if (parseChart.out) {
        print(JSON.stringify(parseChart.out));
    } else {
        print("failed to parse");
    }
}

