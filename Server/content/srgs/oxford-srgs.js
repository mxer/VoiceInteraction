//
// https://www.projectoxford.ai/doc/speech/REST/Recognition
// 

function applySrgsToOxfordResults(grammar, result) {
    for (var i = 0; i < result.results.length; i++) {
        var alt = result.results[i];
        var transcript = alt.lexical;
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
    return "{SML: {}}"
}
