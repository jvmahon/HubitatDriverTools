library (
        base: "driver",
        author: "jvm33",
        category: "zwave",
        description: "Some useful groovy tools",
        name: "groovyHelpeTools",
        namespace: "zwaveTools",
        documentationLink: "https://github.com/jvmahon/HubitatDriverTools",
        version:"0.0.1",
		dependencies: "",
		librarySource:""

)

// nested map merge.
// deep merges the Map specified on the left into the one on the right. https://e.printstacktrace.blog/how-to-merge-two-maps-in-groovy/
def nestedMerge(Map lhs, Map rhs) {
    return rhs.inject(lhs.clone()) { map, entry ->
        if (map[entry.key] instanceof Map && entry.value instanceof Map) {
            map[entry.key] = nestedMerge(map[entry.key], entry.value)
        } else if (map[entry.key] instanceof Collection && entry.value instanceof Collection) {
            map[entry.key] += entry.value
        } else {
            map[entry.key] = entry.value
        }
        return map
    }
}

Map.metaClass.nestedMerge << { Map rhs -> deepMerge(delegate, rhs) } 
 // usage Map e = a.deepMerge(b)