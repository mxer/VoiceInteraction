//
//  srgs.js
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
// encoding SRGS grammars in javascript

function Grammar(root) {
    this.$root = root;
    this.VOID = [OneOf([])];
    this.NULL = [];
    this.GARBAGE = []; 
    this["grammar:dictation"] = this.VOID;
    this.$check = function() {
        for (var i in this) {
            if (i !== "$root" && i !== "$check" && i != "AddWordTransition") {
                try {
                    checkSequenceExpansion(this[i]);
                } catch(err) {
                    throwRuleError("When checking grammar rule '" + i + "'", err);
                }
            }
        }
    }
    var self = this;
    this.AddWordTransition = function(rule, words, replace) {
        var ruleref = "#"+rule;
        var alternatives = [];
        for (var i = 0; i < words.length; i++){
            alternatives.push(Item(words[i]));
        }
        if (replace) {
            self[ruleref] = [OneOf(alternatives)];
        } else {
            self[ruleref].push(OneOf(alternatives));
        }
        self.$check();
    }
}


//////////////////////////////////////////////////////////////////////
// rule expansion constructors

function SrgsTokenize(toks) {
    // remove punctuation
    toks = String(toks).replace(/[;.,?!]/g, " ");
    // make lowercase and remove whitespace
    toks = String(toks.trim().toLowerCase());
    if (toks.length > 0) {
        var ts = toks.split(/\s+/)
        return ts;
    }
    return [];
 }


// sequences are ordinary arrays
function SrgsNormalizeItem(seq) {
    if (seq == undefined) {
        return []
    }
    if (seq.constructor == Array) {
        var result = []
        for (var i = 0; i < seq.length; i++) {
            var tok = seq[i];
            if (tok.constructor == String) {
                var ts = SrgsTokenize(tok);
                if (ts.length > 1) {
                    result.push(ts);
                } else if (ts.length > 0) {
                    result.push(ts[0]);
                }
            } else if (tok.constructor == Array) {
                result.push(SrgsNormalizeItem(tok));
            } else {
                result.push(tok);
            }
        }
        return result;
    } else if (seq.constructor == String) {
        return SrgsTokenize(seq);
    } else {
        return [seq];
    }
}

function Item(seq) {
    var normalized = SrgsNormalizeItem(seq);
//    print(seq +" => "+ normalized);
    return normalized;
}

function Sequence(seq) {
    return seq;
}

function Ref(ref) {
    return new RefClass(ref);
}

function Tag(tag) {
    return new TagClass(tag);
}

function OneOf(alternatives) {
    return new OneOfClass(Item(alternatives));
}

function Repeat(min, max, sequence) {
    return new RepeatClass(min, max, Item(sequence));
}

function Optional(sequence) {
    return new RepeatClass(0, 1, Item(sequence));
}


//////////////////////////////////////////////////////////////////////
// rule expansion classes

function RefClass(ruleref) {
    this.content = ruleref;
    this._string = "$" + ruleref;
    this.toString = function toString() {return this._string}
}
    
function TagClass(tag) {
    this.content = tag;
    this._string = "{" + tag + "}";
    this.toString = function toString() {return this._string}
}

function OneOfClass(alternatives) {
    this.content = alternatives;
    this._string = "(" + alternatives.join("|") + ")";
    this.toString = function toString() {return this._string}
}

function RepeatClass(min, max, sequence) {
    this.min = min;
    this.max = max;
    this.content = sequence;
    this._string = this.content + "<" + this.min + "-" + (this.max==Infinity ? "" : this.max) + ">"
    this.toString = function toString() {return this._string}
}

//////////////////////////////////////////////////////////////////////
// checking rule expansions

function throwRuleError(message, error) {
    if (error == undefined) {
        throw TypeError(message);
    } else {
        throw TypeError(message + "; " + error.message);
    }
}

function checkSequenceExpansion(sequence) {
    try {
        if (sequence.constructor !== Array) {
            throwRuleError("Expected Array, found " + sequence.constructor.name + ": "+sequence);
        }
        for (var i in sequence) {
            if (sequence[i].constructor == Array) {
                checkSequenceExpansion(sequence[i]);
            } else if (sequence[i].constructor != String) {
                sequence[i].checkExpansion();
            }
        }
    } catch(err) {
        throwRuleError("When checking sequence expansion", err);
    }
}

RefClass.prototype.checkExpansion = function checkExpansion() {
    if (this.content.constructor !== String) {
        throwRuleError("When checking Ref content; Expected String, found " + 
                       this.content.constructor.name);
    }
}

TagClass.prototype.checkExpansion = function checkExpansion() {
    if (this.content.constructor !== String) {
        throwRuleError("When checking Tag content; Expected String, found " + 
                       this.content.constructor.name);
    }
}

OneOfClass.prototype.checkExpansion = function checkExpansion() {
    try {
        if (this.content.constructor !== Array) {
            throwRuleError("Expected Array, found " + this.content.constructor.name +": "+this.content);
        }
        for (var i in this.content) {
            checkSequenceExpansion(this.content[i]);
        }
    } catch(err) {
        throwRuleError("When checking OneOf content", err);
    }
}

RepeatClass.prototype.checkExpansion = function checkExpansion() {
    try {
        if (this.min.constructor !== Number || this.max.constructor !== Number) {
            throwRuleError("Expected min/max to be Number, found " + 
                           this.min.constructor.name + "/" + this.max.constructor.name);
        }
        if (!(0 <= this.min && this.min <= this.max)) {
            throwRuleError("Expected 0 <= min <= max, found " + this.min + "/" + this.max);
        }
        checkSequenceExpansion(this.content);
    } catch(err) {
        throwRuleError("When checking Repeat content", err);
    }
}

