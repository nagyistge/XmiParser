import groovy.transform.TypeChecked
import groovy.util.slurpersupport.Attribute

/**
 @author Jason Mathews, MITRE Corporation
 Created on 10/8/2014.

  -----------------------------------------------------------------------------------

  Copyright (C) 2014 The MITRE Corporation. All Rights Reserved.

  The program is provided "as is" without any warranty express or implied, including
  the warranty of non-infringement and the implied warranties of merchantability and
  fitness for a particular purpose.  The Copyright owner will not be liable for any
  damages suffered by you as a result of using the Program.  In no event will the
  Copyright owner be liable for any special, indirect or consequential damages or
  lost profits even if the Copyright owner has been advised of the possibility of
  their occurrence.
 */
class XmiValidator extends XmiParser {

  // following two fields are populated in populateElement()
  // and made available when validateAttributes() is called

  /*
  * attributes contains a list of attributes as strings with each group of attributes
  * preceded in list with the Attributes object that contained it which has
  * the parent element name.
  */
  protected Collection attributes
  protected Set<String> subinterfaces

  XmiValidator(File file) {
    super(file)
  }

  void validate() {
    // check consistent abstract of elements with same parents
    boolean hasMismatch = false
    Map<String, AbstractStat> abstractMap = new HashMap<String, AbstractStat>()
    elements.each{ String key, elt ->
      if (key.startsWith('package:')) return
      Set<String> parentSet = parents.get(key)
      if (parentSet) {
        parentSet.each { String parent ->
          def stat = abstractMap.get(parent)
          if (!stat) {
            stat = new AbstractStat()
            abstractMap.put(parent, stat)
          }
          if (stat.add(elt)) hasMismatch = true
        }
      }
    }
    if(hasMismatch) {
      println "\nPossible inconsistent abstract elements with same parent:"
      // println abstractMap
      abstractMap.each { String parent, stat ->
        if (stat.hasAbstract && stat.hasNonAbstract) {
          hasMismatch = true
          println parent
          stat.list.each { elt ->
            String name = elt.@name.text()
            if (elt.@isAbstract.text() == "true") name += "(*)"
            println "\t" + name
          }
        }
      }
      println "(*) == abstract\n"
    }

    String packageName
    elements.each { String name, elt ->
      // if elt is null then refers to package
      if (name.startsWith('package:')) {
          packageName = name.substring(8)
        return
      }
      if (elt == null) return

      populateElement(elt, name)

      if (!attributes.isEmpty()) {
        validateAttributes(packageName, elt, name)
      }
    }
  } // validate

  // -----------------------------------------------

  /**
   * Validate collection of attributes of given element.
   *
   * @param elt element to validate
   * @param name name of element
   */
  void validateAttributes(String packageName, elt, String name) {
    def attrsSet = new HashSet()
    def sortedAttrMap = new TreeMap<String, String>()
    String parentName
    attributes.each { attr ->
      if (attr instanceof groovy.util.slurpersupport.Attributes) {
        parentName = attr.text()
        //println "XX: $parentName"
      } else {
        String oldValue = sortedAttrMap.put(attr, parentName)
        if (oldValue != null && oldValue != parentName) println "ERROR: attribute conflict: $attr $oldValue $parentName"
        if (!attrsSet.add(attr)) println "WARN: duplicate attribute $attr in $name"
        //println "\t$attr"
      }
    }
  }

  // -----------------------------------------------

  void populateElement(elt, String name) {
    String type = elt.'@xmi:type'.text()
    boolean isInterface = type == 'uml:Interface'
    Set<String> parentNames = parents.get(name)
    if (!isInterface && parentNames) {
      if (parentNames.size() > 1) println "INFO: multiple class inheritance $name : ${parentNames.size()} $parentNames"
    }

    attributes = new ArrayList()
    checkAttributes(elt)

    // recursively add attributes from parents
    if (parentNames) {
      Set<String> visitedClasses = new HashSet<>()
      parentNames.each { String parentName ->
        def parent = elements.get(parentName)
        if (parent && visitedClasses.add(parentName)) {
          // println "\t$parentName"
          checkAttributes(parent)
          // allow for multiple inheritance
          checkParents(parentName, name, visitedClasses)
        }
      }
    }

    subinterfaces = new TreeSet<String>()
    def ref = interfaceMap[name]
    if (ref) {
      // All Known Subinterfaces: e.g. ObjectInput extends DataInput interface
      while (ref) {
        subinterfaces.add(ref)
        ref = interfaceMap[ref]
      }
    } // if ref

    if (!isInterface) {
      // class
      def refs = connectors.connector.findAll {
        it.source.model.@name == name && it.target.model.@type == 'Interface' && it.properties.@ea_type == 'Realisation'
        // ignores Aggregation and Association connections
      }
      /*
      EncounterEvent: ActionPerformance Class
      EncounterEvent: Condition Class
      EncounterEvent: Condition Class
      EncounterEvent: Condition Class
      EncounterEvent: EncounterDescriptor Interface
      EncounterEvent: Performance Interface
      EncounterRequest > interface Order
      etc.
     */
      if (!refs.isEmpty()) {
        refs.each {
          def tgtName = it.target.model.@name.text()
          // recursively add interfaces and their super-interfaces; e.g. CommunicationOrder <= Order <= ActionPhase
          while (tgtName && subinterfaces.add(tgtName)) {
            tgtName = interfaceMap[tgtName]
          }
        }
      } // if refs
    } // if class

    // add attributes and aggregate connections from interfaces
    if (subinterfaces) {
      subinterfaces.each {
        def parent = elements.get(it)
        checkAttributes(parent)
      }
    }
  }

  // -----------------------------------------------

  protected void checkAttributes(elt) {
    int atCnt = 0
    def eltName = elt.@name
    //boolean self = targetName == null
    // println eltName.getClass().name // groovy.util.slurpersupport.Attributes
    //println "XXX: elt="+elt.list() // debug
    //String eltType = elt.@'xmi:type'?.text()?.toLowerCase() ?: ''
    //if (eltType.startsWith('uml:')) eltType = eltType.substring(4)
    //String eltType = 'uml:Interface' == elt.'@xmi:type'.text() ? 'interface' : 'class'

    // NOTE: when flatListMode is true we're making second pass through same class/element so want to suppress warnings

    // populate namedAttrs + index + types + propTypes fields
    elt.ownedAttribute.each {
      def attrName = it.@name.text()
      if (attrName) {
        if (atCnt++ == 0) attributes.add(eltName) // add attribute for element that attributes are contained within
        attributes.add(attrName) // String
      }
    }
    dumpAggregates(elt, atCnt, eltName.text())
  }

  // -----------------------------------------------

  private void dumpAggregates(elt, int atCnt, String name) {
    def aggregateList = aggregation.get(name)
    if (aggregateList) {
      if (atCnt == 0 || attributes.isEmpty()) {
        // first element must be name attribute
        attributes.add(new groovy.util.slurpersupport.Attributes(
                new Attribute('name', name, elt, '', EMPTY_MAP), 'ownedAttribute', EMPTY_MAP))
      }
    }
  }

  // -----------------------------------------------

  @TypeChecked
  protected void checkParents(String parentName, String name, Set visitedClasses) {
    Set<String> parentNames = parents.get(parentName)
    parentNames.each { String pName ->
      def parent = elements.get(pName)
      if (parent && visitedClasses.add(pName)) {
        // println "\t$pName"
        checkAttributes(parent)
        checkParents(pName, name, visitedClasses)
      } // else println "dup/other: $pName"
    }
  }

  // -----------------------------------------------

  static class AbstractStat {
    boolean hasAbstract
    boolean hasNonAbstract
    List<Object> list = new ArrayList<>()

    boolean add(def elt) {
      list.add(elt)
      if (elt.@isAbstract.text() == 'true')
        hasAbstract = true
      else
        hasNonAbstract = true
      return hasNonAbstract && hasAbstract
    }
    // String toString() { return "" + hasNonAbstract + " " + hasAbstract + " " + list }
  }

}
