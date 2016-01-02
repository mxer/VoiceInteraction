//
// https://www.ibm.com/smarterplanet/us/en/ibmwatson/developercloud/doc/speech-to-text/quick-curl.shtml
//
// https://stream.watsonplatform.net/speech-to-text/api/v1/recognize
//
// {
//     "results": [
//     {
//       "alternatives": [
//             {
//                   "confidence": 0.9999
//                   "transcript": "through colorado on sunday "
//             }
//          ],
//          "final": true
//       }
//    ],
//    "result_index": 0
// }
// 

function applySrgsToWatsonResults(grammar, result) {
    for (var i = 0; i < result.results.length; i++) {
        if (result.results[i].final) {
            for (var j = 0; j < result.results[i].alternatives.length; j++) {
                var alt = result.results[i].alternatives[j];
                var transcript = alt.transcript;
                var chart = parseString(transcript, grammar, grammar.$root);
                var out = {};
                if (chart.out) {
                    out = chart.out;
                }
                //out["@confidence"] = alt.confidence;
                var confidence = alt.confidence ? alt.confidence : 1.0;
                out["@utteranceConfidence"] = confidence;
                out["@text"] = transcript;
                var sml = {
                    SML: out
                }
                return JSON.stringify(sml);
            }
        }
    }
    return "{SML: {}}"
}
