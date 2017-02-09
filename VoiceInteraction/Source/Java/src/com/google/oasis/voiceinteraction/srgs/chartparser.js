//
//  chartparser.js
//  Copyright (C) 2009, 2010, Peter Ljunglöf. All rights reserved.
//
/*
  This program is free software: you can redistribute it and/or modify
  it under the terms of the GNU Lesser General Public License as published 
  by the Free Software Foundation, either version 3 of the License, or
  (at your option) any later version.
  
  This program is distributed in the hope that it will be useful,
  but WITHOUT ANY WARRANTY; without even the implied warranty of
  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
  GNU General Public License for more details.
  
  You should have received a copy of the GNU General Public License
  and the GNU Lesser General Public License along with this program.  
  If not, see <http://www.gnu.org/licenses/>.
*/


//////////////////////////////////////////////////////////////////////
// by default we do not produce debugging output:

function LOG(str) {}

// if you want to debug the parsing process, 
// define the following before calling the parse() function:
/*
 function LOG(msg) {console.log(String(msg));}
 */


//////////////////////////////////////////////////////////////////////
// by default we include parse trees in the parse results:

function makeTree(label, children, data) {
    return {label: label, children: children, data: data};
}

// ...which can result in bigger parse charts and longer execution times.
// if you don't want to include parse trees, 
// define the following before calling the parse() function:
/*
 makeTree = false;
 */


//////////////////////////////////////////////////////////////////////
// we need to be able to clone objects between different edges
// borrowed from http://keithdevens.com/weblog/archive/2007/Jun/07/javascript.clone

function clone(obj){
    if (obj == null || typeof(obj) != 'object') {
        return obj;
    }
    var temp = new obj.constructor();
    for (var key in obj) {
        temp[key] = clone(obj[key]);
    }
    temp.constructor = obj.constructor;
    return temp;
}

function Rules() {
}


//////////////////////////////////////////////////////////////////////
// objects are by default printed "[Object]"
// to be able to use objects in hash tables, 
// we need a better string representation

function stringRepr(obj) {
    if (obj == null || typeof(obj) != 'object') {
        return String(obj);
    }
    var str = "{";
    for (var key in obj) {
        str += key + ":" + stringRepr(obj[key]) + ";";
    }
    return str + "}";
}


//////////////////////////////////////////////////////////////////////
// parse chart
// conceptually this is a set of edges, but it is optimized

function Chart(numberOfWords) {
    this.numberOfWords = numberOfWords;
    this.passives = new Array(numberOfWords);
    this.actives = new Array(numberOfWords);
    this.actions = [];
    for (var i = 0; i <= numberOfWords; i++) {
        this.passives[i] = {};
        this.actives[i] = {};
    }
    
    // Chart.add(edge)
    // add the edge to the chart, return true if the chart was changed 
    // (i.e. if the chart didn't already contain the edge)
    this.add = function add(edge) {
        var subchart, cat;
        if (edge.isPassive) {
            subchart = this.passives[edge.start];
            cat = edge.lhs;
        } else {
            subchart = this.actives[edge.end];
            cat = edge.next.content;
        }
        if (!(cat in subchart)) {
            subchart[cat] = {};
        }
        if (edge in subchart[cat]) {
            return false;
        } else {
            subchart[cat][edge] = edge;
            return true;
        }
    }
    
    // Chart.treesForRule(lhs, start, end)
    // return all parse trees for the given lhs, start, and end
    //  - start, end are optional; defaults to 0, numberOfWords
    this.treesForRule = function treesForRule(lhs, start, end) {
        start = start || 0;
        end = end || numberOfWords;
        var trees = [];
        var finalEdges = this.passives[start][lhs];
        for (var i in finalEdges) {
            if (finalEdges[i].end == end) {
                trees.push(finalEdges[i].tree);
            }
        }
        return trees;
    }
    
    // Chart.allEdges() / Chart.allPassiveEdges() / Chart.allActiveEdges()
    // return an array of all (passive/active) edges in the chart
    this.allEdges = function allEdges() {
        return this.allPassiveEdges().concat(this.allActiveEdges());
    }
    this.allPassiveEdges = function allPassiveEdges() {
        var edges = [];
        for (var i in this.passives) 
            for (var j in this.passives[i]) 
                for (var k in this.passives[i][j])
                    edges.push(this.passives[i][j][k]);
        return edges;
    }
    this.allActiveEdges = function allActiveEdges() {
        var edges = [];
        for (var i in this.actives) 
            for (var j in this.actives[i]) 
                for (var k in this.actives[i][j])
                    edges.push(this.actives[i][j][k]);
        return edges;
    }
    
    // Chart.statistics()
    // return the number of edges in the chart
    this.statistics = function statistics() {
        var passives = this.allPassiveEdges().length;
        var actives = this.allActiveEdges().length;
        return {nrEdges: passives+actives, nrPassiveEdges: passives, nrActiveEdges: actives};
    }
}


//////////////////////////////////////////////////////////////////////
// parse edges: passive and active

function PassiveEdge(start, end, lhs, out, tree) {
    this.start = start;
    this.end = end;
    this.lhs = lhs;
    this.out = out;
    this.tree = tree;
    this.isPassive = true;
    
    var str = "[" + start + "-" + end + "] $" + lhs + " := " + 
                stringRepr(out) + " / " + stringRepr(tree);
    this._string = str;
    this.toString = function toString() {return this._string;} 
}

function ActiveEdge(start, end, lhs, next, rest, out, rules, children) {
    this.start = start;
    this.end = end;
    this.lhs = lhs;
    this.next = next;
    this.rest = rest;
    this.out = out;
    this.rules = rules;
    this.children = children;
    this.isPassive = false;
    
    var str = "<" + start + "-" + end + "> $" + lhs + " -> " + next + 
                ", " + rest + " := " + stringRepr(out) + " <- " + 
                stringRepr(rules) + " / " + stringRepr(children);
    this._string = str;
    this.toString = function toString() {return this._string;} 
}


//////////////////////////////////////////////////////////////////////
// the main parsing function: a simple top-down chartparser
//  - 'words' is an array of strings
//  - 'grammar' is a hash table of left-hand-sides mapping to arrays of right-hand-sides
//  - 'root' is the starting category (a string)
//    if unspecified, use the '$root' property of the grammar
//  - 'filter' is an optional left-corner filter 
//    (a mapping from categories/rule-refs to words)
//    if specified, it is used when predicting new edges
// returns the final chart

function parseString(wordString, grammar, root, filter) {
    return parse(SrgsTokenize(wordString), grammar, root, filter);
}

function parse(words, grammar, root, filter) {
    if (!root) {
        root = grammar.$root;
    }
    try {
        java.lang.System.err.println("parsing: "+words);
    } catch (ignored) {
    }
    var chart = new Chart(words.length);
    var agenda = [];
    var leftCornerFilter;
    if (filter == undefined) {
        leftCornerFilter = function() {return true};
    } else {
        leftCornerFilter = function leftCornerFilter(ruleref, position) {
            var leftCorners = filter[ruleref];
            return leftCorners ? words[position] in leftCorners : true;
        }
    }
    
    // add an edge to the chart and the agenda, if it does not already exist
    function addToChart(inference, start, end, lhs, rhs, out, rules, children) {
        var edge;
        var tagAction;
        if (rhs.length > 0) {
            var next = rhs[0];
            var rest = rhs.slice(1);
            switch (next.constructor) {
                    
                case Array:
                    // the next symbol is a sequence
                    addToChart(inference+",SEQUENCE", start, end, lhs, 
                               next.concat(rest), out, rules, children);
                    return;
                    
                case RepeatClass:
                    // the next symbol is a repetition
                    var min = next.min;
                    var max = next.max;
                    // skip repeat 
                    if (min <= 0) {
                        addToChart(inference+",SKIP", start, end, lhs, 
                                   rest, out, rules, children);
                    }
                    // repeat 
                    if (max > 0) {
                        var content = next.content;
                        var rhs = [content];
                        if (max > 1) {
                            rhs.push(Repeat(min ? min-1 : min, max-1, content));
                        }
                        addToChart(inference+",REPEAT", start, end, lhs, 
                                   rhs.concat(rest), out, rules, children);
                    }
                    return;
                    
                case OneOfClass:
                    // the next symbol is a disjunction
                    var oneof = next.content;
                    for (var i in oneof) {
                        var rhs = oneof[i].concat(rest);
                        addToChart(inference+",ONEOF", start, end, lhs, 
                                   rhs, out, rules, children);
                    } 
                    return;
                    
                case TagClass:
                    // the next symbol is a semantic action
                out = clone(out);
                rules = clone(rules);
                children = clone(children);
                var script = next.content;
                if (out.script) {
                    script = out.script + "; "+script;
                }
                var evalu = function(rules, out) {
//                    print("evaluating: "+script);
                    eval(script);
                    return out;
                }
                out.action = evalu;
                out.script = script;
                addToChart(inference+",TAG", start, end, lhs, 
                           rest, out, rules, children);
                return;
            case String:
                {
                }
            }
            edge = new ActiveEdge(start, end, lhs, next, rest, out, rules, children);
            
        } else {
            var tree;
            if (makeTree) {
                tree = makeTree(lhs, children, out);
            } else {
                tree = out;
            }
            edge = new PassiveEdge(start, end, lhs, out, tree);
        }
        
        // try to add the edge; if successful, also add it to the agenda
        if (chart.add(edge)) {
            LOG("+ " + inference + ": " + edge);
            agenda.push(edge);
        }
    }
    
    // seed the agenda with the starting rule
    addToChart("INIT", 0, 0, root, grammar[root], {}, new Rules(), []);
    
    // main loop
    while (agenda.length > 0) {
        var edge = agenda.pop();
        var start= edge.start;
        var end  = edge.end;
        var lhs  = edge.lhs;
        var next = edge.next;
        LOG(edge);
        //print("matched? "+next + " at " +end + " in " +words);        
        if (edge.isPassive) {
            // combine
            var actives = chart.actives[start][lhs];
            for (var i in actives) {
                var active = actives[i];
                var rules = clone(active.rules);
                rules[edge.lhs] = clone(edge.out);
                var children;
                if (makeTree) {
                    children = clone(active.children);
                    children.push(clone(edge.tree));
                }
                addToChart("COMBINE", active.start, end, active.lhs, 
                           
                           active.rest, active.out, rules, children);
            }
            
        } else if (next.constructor == RefClass) {
            var ref = next.content;
            // combine
            var passives = chart.passives[end][ref];
            for (var i in passives) {
                var passive = passives[i];
                var rules = clone(edge.rules);
                rules[passive.lhs] = clone(passive.out);
                var children;
                if (makeTree) {
                    children = clone(edge.children);
                    children.push(clone(passive.tree));
                }
                addToChart("COMBINE", start, passive.end, lhs, 
                           edge.rest, edge.out, rules, children);
            }
            // predict
            if (ref in grammar) {
                if (leftCornerFilter(ref, end)) {
                    addToChart("PREDICT", end, end, ref, 
                               grammar[ref], {}, {}, []);
                }
            } else {
                throw new Error("Can't resolve ruleref "+ ref);
            }
        } else if (next == words[end]) {
            // scan
//            print("matched "+next.constructor + " at " +end + " in " +words);
            var children;
            if (makeTree) {
                children = clone(edge.children);
                children.push(next);
            }
            addToChart("SCAN", start, end+1, lhs, 
                       edge.rest, edge.out, edge.rules, children, end == words.length-1);
        }
    }
    var trees = chart.treesForRule(root);
    chart.outs = [];
    //print("trees="+trees.length);
    for (var i = 0; i < trees.length; i++) {
        //print(treeString(trees[i]));
        var actions = [];
        var out = {};
        var rules = {};
        function evaluate(t, rules, out) {
//            print("visit: "+ t.label);
//            print("action: "+ t.data.script);
            if (t.children) {
                var terminals = true;
                for (var i = 0; i < t.children.length; i++) {
                    var c = t.children[i];
                    if (c.constructor != String) {
                        terminals = false;
                    }
                    if (c.data) {
                        out = evaluate(t.children[i], rules, out);
                    }
                }
                if (terminals) {
                    var str = "";
                    var sep = "";
                    for (var i = 0; i < t.children.length; i++) {
                        var c = t.children[i];
                        str += sep;
                        str += c.replace('"', "");
                        sep = " ";
                    }
                    if (str.length > 0) {
                        //var script = 'out="'+str+'";';
                        actions.push(function(rules, out) {
                            return str;
                        });
                    }
                }
            }
            if (t.data.action) {
                actions.push(t.data.action);
            } else if (t.data.words) {
                var str = "";
                var sep = "";
                for (var j = 0; j < t.data.words.length; j++) {
                    str += sep;
                    str += t.data.words[j]
                    sep = " ";
                }
                actions.push(function(rules, out) {
                    return str;
                });
            }
        }
        evaluate(trees[i]);
        for (var j = 0; j < actions.length; j++) {
            var latest1 = clone(out);
            rules.latest = function() {
                //print("latest==>"+latest1);
                return latest1;
            }
            out = actions[j](rules, out instanceof Object ? out : {});
            //print("out=>"+stringRepr(out));
        }
        chart.outs.push(out);
        if (!chart.out) {
            chart.out = out;
        } else {
            for (k in out) {
                chart.out[k] = out[k];
            }
        }
    }
    return chart;
}

function treeString(tree) {
    if (typeof(tree) == 'object' && tree.label) {
        str = "(" + tree.label
        if (tree.children) {
            for (var i in tree.children) {
                str += " " + treeString(tree.children[i])
            }
        }
        return str + ")"
    } else {
        return stringRepr(tree)
    }
}


