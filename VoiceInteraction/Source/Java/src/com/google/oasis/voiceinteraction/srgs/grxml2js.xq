(: Query which converts an SRGS grxml document to Javascript (see srgs.js) :)

declare namespace srgs = "http://www.w3.org/2001/06/grammar";
declare option saxon:output "omit-xml-declaration=yes";

declare function local:trimCode($s) as xs:string {
   fn:replace(fn:translate($s, '&#xA;', '&#9996;'), '&#9996;', "\\n")
};

declare function local:trim($s) as xs:string {
   fn:normalize-space($s)
};

declare function local:translateCmd($cmds) as xs:string {
  let $vals := for $cmd in $cmds where ($cmd/self::srgs:item or $cmd/self::srgs:one-of or $cmd/self::srgs:ruleref or $cmd/self::srgs:tag or $cmd/self::text())
    return
       if ($cmd/self::text())
         then local:translateItemBody($cmd)
          else if (local-name($cmd) = "item")
             then local:translateItem($cmd)
                else if (local-name($cmd) = "one-of")
                     then local:translateOneOf($cmd)
                else if (local-name($cmd) = "ruleref")
                    then local:translateRuleRef($cmd)
                else if (local-name($cmd) = "tag")
                     then local:translateTag($cmd)
                        else ""
   return fn:concat("[", fn:string-join($vals[local:trim(.)!=""], ",&#10;"), "]")
};

declare function local:translateRepeat($cmd) as xs:string {
   let $toks := fn:tokenize($cmd/@repeat, "-")
   return fn:concat("Repeat(", $toks[1], ", ", $toks[2], ", ", local:translateItemBody($cmd), ")")
};

declare function local:translateItemBody($cmd) as xs:string {
   let $nodes := for $n in $cmd/node()
       return if ($n/self::text())
          then if (local:trim($n)) then fn:concat('"', fn:replace(local:trim($n), '"', '\\"'), '"') else ""
          else local:translateCmd($n)
   let $vals := $nodes[local:trim(.)]
   return if (fn:count($vals) = 1) then fn:string($vals) else fn:concat("[", fn:string-join($nodes[local:trim(.)], ","), "]")
};

declare function local:translateItem($cmd) as xs:string {
   if ($cmd/@repeat)
   then local:translateRepeat($cmd)
   else fn:concat("Item(", local:translateItemBody($cmd), ")")
};

declare function local:translateOneOf($cmd) as xs:string {
   fn:concat("OneOf(", local:translateCmd($cmd/*), ")")
};

declare function local:translateRuleRef($cmd) as xs:string {
   fn:concat('Ref("', $cmd/@uri , '")')
};

declare function local:translateTag($cmd) as xs:string {
   fn:concat('Tag("', fn:replace(local:trimCode($cmd), '"', '\\"'), '")')
};

fn:concat('var grammar = new Grammar("#', ./srgs:grammar/@root, '");', "&#10;",
fn:string-join(for $rule in ./srgs:grammar/srgs:rule 
    return fn:concat('grammar["#',
           $rule/@id, '"] = ',
           local:translateItemBody($rule), ";"), "&#10;"))
