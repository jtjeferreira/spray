$(function() {
    // disabled for old milestone versions (containing 'M' in the version)
    var versionRE = new RegExp('/documentation/([^/M]*)/spray-routing/.*')
    var match = versionRE.exec(document.location.pathname);

    if (match) {
        var version = match[1]; // capture group 1

        var directives = [];
        // convert directivesMap into a flat list of {group, name} entries, only called once per page
        function init() {
            for (var i in DirectivesMap) {
                var group = DirectivesMap[i];
                var entries = group.entries.split(' ');
                for (var j in entries) {
                    var d = entries[j];
                    directives.push({
                        group: group.group+'-directives',
                        name: d
                    });
                }
            }
        }

        function findDirective(name) {
            for (var i in directives) {
                var t = directives[i];
                if (t.name === name)
                    return t;
            }
        }
        function directiveLinkTarget(directive) {
            return '/documentation/'+version+'/spray-routing/'+directive.group+'/'+directive.name+'/';
        }
        function nextCharIs(e, ch) {
            if (e.nextSibling) {
                var next = e.nextSibling;
                var text = next.textContent;
                if (next.textContent.indexOf(ch) === 0) return true;
                else if (next.textContent === " ") return nextCharIs(next, ch);
            }
            return false;
        }
        init();

        $('.highlight-scala .n, .literal .pre').each(function(i, e) {
            // crude heuristic to exclude false positives in "ctx.request.method" or "ctx.complete"
            if ((e.previousSibling && e.previousSibling.textContent === ".") ||
                nextCharIs(e, ":") || nextCharIs(e, "=")) return;
            var ele = $(e);
            var name = ele.text();
            var directive = findDirective(name);
            if (directive)
                ele.wrap('<a href="'+directiveLinkTarget(directive)+'"/>');
        });
    }

    var suggestionsEngine = new Bloodhound({
        datumTokenizer: function(d) {
          return Bloodhound.tokenizers.whitespace(d.num);
        },
        queryTokenizer: Bloodhound.tokenizers.whitespace,
        name: "documentation",
        limit: 10,
        remote: "/search/documentation/typeahead?terms=%QUERY"
    });

    suggestionsEngine.initialize();

    $('.typeahead').typeahead(
    	  { minLength: 3 },
    	  {
    		    source: suggestionsEngine.ttAdapter(),
    		    templates: {
      			    empty: function(data) {
                    return [
        			          '<span class="tt-empty-message">',
        			          'Could not find results for: <strong>'+data.query+'</strong>',
        			          '</span>'].join('\n')
                },
      			    suggestion: function(data) {
                    return '<div>'+data.extra.parent+':&nbsp;<strong>'+data.name+'</strong></div>'
                }
      		  }
    	  }
    )
    .bind("typeahead:selected", function(event, datum) {
        window.location = datum.url;
    });
});
