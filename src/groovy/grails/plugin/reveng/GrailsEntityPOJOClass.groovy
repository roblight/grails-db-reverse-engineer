/* Copyright 2006-2010 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package grails.plugin.reveng

import grails.util.GrailsNameUtils

import org.hibernate.cfg.Configuration
import org.hibernate.cfg.Environment
import org.hibernate.mapping.Column
import org.hibernate.mapping.ForeignKey
import org.hibernate.mapping.ManyToOne
import org.hibernate.mapping.PersistentClass
import org.hibernate.mapping.Property
import org.hibernate.mapping.Table
import org.hibernate.mapping.UniqueKey
import org.hibernate.mapping.Value
import org.hibernate.tool.hbm2x.Cfg2HbmTool
import org.hibernate.tool.hbm2x.Cfg2JavaTool
import org.hibernate.tool.hbm2x.pojo.EntityPOJOClass
import org.hibernate.type.IntegerType
import org.hibernate.type.LongType

/**
 * @author <a href='mailto:burt@burtbeckwith.com'>Burt Beckwith</a>
 */
class GrailsEntityPOJOClass extends EntityPOJOClass {

	private static final Map<String, String> typeNameReplacements = [
		'boolean': 'Boolean',
		'byte': 'Byte',
		'char': 'Character',
		'double': 'Double',
		'int': 'Integer',
		'float': 'Float',
		'long': 'Long',
		'short': 'Short']

	private PersistentClass clazz
	private Cfg2HbmTool c2h
	private Configuration configuration
	private ConfigObject grailsConfig
	private String newline = System.getProperty('line.separator')
	private newProperties = []

	GrailsEntityPOJOClass(PersistentClass clazz, Cfg2JavaTool cfg, Cfg2HbmTool c2h,
			Configuration configuration, ConfigObject grailsConfig) {
		super(clazz, cfg)
		this.clazz = clazz
		this.c2h = c2h
		this.configuration = configuration
		this.grailsConfig = grailsConfig
	}

	@Override
	String getPackageDeclaration() { super.getPackageDeclaration() - ';' }

	@Override
	Iterator getAllPropertiesIterator() {
		def props = []
		def idProperty = getIdentifierProperty()
		def versionProperty = getVersionProperty()

		super.getAllPropertiesIterator().each {
			if (it == versionProperty) {
				return
			}

			if (it == idProperty) {
				if (c2j.isComponent(it)) {
					it.value.propertyIterator.each { idProp ->
						props << idProp
					}
					return
				}
				if (it.name == 'id' || it.type instanceof LongType || it.type instanceof IntegerType) {
					return
				}
			}

			if (it.value instanceof ManyToOne || it.value instanceof org.hibernate.mapping.Set) {
				return
			}

			props << it
		}
		props.sort{ it.name }.iterator()
	}

	String renderId() {
		def idProperty = getIdentifierProperty()
		if (c2j.isComponent(idProperty)) {
			def idDef = new StringBuilder('    id composite: [')
			String delimiter = ''
			idProperty.value.propertyIterator.each {
				idDef.append delimiter
				idDef.append "\"${findRealIdName(it)}\""
				delimiter = ', '
			}
			idDef.append ']'
			idDef.append newline
			return idDef.toString()
		}

		if (idProperty.name != 'id' || idProperty.value.identifierGeneratorStrategy == 'assigned') {
			def idDef = new StringBuilder('    id ')
			String delimiter = ''

			if (idProperty.name != 'id') {
				idDef.append delimiter
				if (idProperty.type instanceof LongType || idProperty.type instanceof IntegerType) {
					String colName = idProperty.columnIterator.next().name
					idDef.append "column: \"$colName\""
				}
				else {
					idDef.append "name: \"$idProperty.name\""
				}
				delimiter = ', '
			}

			if (idProperty.value.identifierGeneratorStrategy == 'assigned') {
				idDef.append delimiter
				idDef.append "generator: \"assigned\""
				delimiter = ', '
			}

			idDef.append newline
			return idDef.toString()
		}

		''
	}

	String renderVersion() {
		if (hasVersionProperty()) {
			if (getVersionProperty().name != 'version') {
				return '    version "' + getVersionProperty().value.columnIterator.next().name +
						'"' + newline
			}
			return ''
		}

		'    version false' + newline
	}

	String renderTable() {
//		if (clazz.table.schema || clazz.table.catalog) {
//			def tableDef = new StringBuilder('    table ')
//			String delimiter = ''
//
//			if (clazz.table.schema) {
//				tableDef.append delimiter
//				tableDef.append "schema: \"$clazz.table.quotedSchema\""
//				delimiter = ', '
//			}
//
//			if (clazz.table.catalog) {
//				tableDef.append delimiter
//				tableDef.append "catalog: \"$clazz.table.catalog\""
//				delimiter = ', '
//			}
//
//			tableDef.append newline
//			return tableDef.toString()
//		}

		''
	}

	@Override
	String generateImports() {
		def fixed = new StringBuilder()
		String imports = super.generateImports().trim()
		String delimiter = ''
		imports.eachLine { String line ->
			line -= ';'
			if (isValidImport(line - 'import ')) {
				fixed.append delimiter
				fixed.append line
				delimiter = newline
			}
		}

		if (needsEqualsHashCode()) {
			fixed.append delimiter
			fixed.append 'import org.apache.commons.lang.builder.EqualsBuilder'
			delimiter = newline
			fixed.append delimiter
			fixed.append 'import org.apache.commons.lang.builder.HashCodeBuilder'
			fixed.append delimiter
		}

		imports = fixed.toString()
		if (imports) {
			return imports + newline + newline
		}

		''
	}

	private boolean isValidImport(String candidate) {
		if ('java.math.BigDecimal'.equals(candidate) ||
		    'java.math.BigInteger'.equals(candidate)) {
			return false
		}

		if (isInPackage(candidate, 'java.io') ||
		    isInPackage(candidate, 'java.net') ||
		    isInPackage(candidate, 'java.util')) {
			return false
		}

		true
	}

	private boolean isInPackage(String candidate, String pkg) {
		if (!candidate.contains(pkg)) {
			return false
		}

		int index = candidate.lastIndexOf('.')
		if (index == -1) {
			return false
		}

		String candidatePackage = candidate[0..index - 1]
		candidatePackage == pkg
	}

	String renderHashCodeAndEquals() {
		if (!needsEqualsHashCode()) {
			return ''
		}

		def hashCodeDef = new StringBuilder('  int hashCode() {')
		hashCodeDef.append newline
		hashCodeDef.append '    def builder = new HashCodeBuilder()'
		hashCodeDef.append newline

		def equalsDef = new StringBuilder('  boolean equals(other) {')
		equalsDef.append newline
		equalsDef.append '    if (other == null) return false'
		equalsDef.append newline
		equalsDef.append '    def builder = new EqualsBuilder()'
		equalsDef.append newline

		getAllPropertiesIterator().each { property ->
			if (c2j.getMetaAsBool(property, 'use-in-equals')) {
				String name = findRealIdName(property)
				if (name != property.name) {
					name += '?.id'
				}
				hashCodeDef.append '    builder.append '
				hashCodeDef.append name
				hashCodeDef.append newline

				equalsDef.append '    builder.append '
				equalsDef.append name
				equalsDef.append ', other.'
				equalsDef.append name
				equalsDef.append newline
			}
		}

		hashCodeDef.append '    builder.toHashCode()'
		hashCodeDef.append newline
		hashCodeDef.append '  }'
		hashCodeDef.append newline

		equalsDef.append '    builder.isEquals()'
		equalsDef.append newline
		equalsDef.append '  }'

		newline + hashCodeDef + newline + equalsDef
	}

	String renderConstraints() {
		def constraints = new StringBuilder()
		getAllPropertiesIterator().each { Property property ->
			if (!getMetaAttribAsBool(property, 'gen-property', true)) {
				return
			}

			def values = [:]
			if (!property.type.isCollectionType() && property.isNullable()) {
				values.nullable = true
			}
			if (property.columnSpan == 1) {
				Column column = property.columnIterator.next()
				if (column.length && column.length != Column.DEFAULT_LENGTH) {
					values.maxSize = column.length
				}

				if (column.scale != 0 && column.scale != Column.DEFAULT_SCALE) {
					values.scale = column.scale
				}

				if (property != getIdentifierProperty() && column.unique) {
					values.unique = true
				}

				clazz.table.uniqueKeyIterator.each { UniqueKey key ->
					if (key.columnSpan == 1 || key.name == clazz.table.primaryKey.name) return
					if (key.columns[-1] == column) {
						def otherNames = key.columns[0..-2].collect { "\"$it.name\"" }
						values.unique = '[' + otherNames.reverse().join(', ') + ']'
					}
				}
			}

			if (values) {
				constraints.append "    $property.name "
				String delimiter = ''
				values.each { k, v ->
					constraints.append delimiter
					constraints.append "$k: $v"
					delimiter = ', '
				}
				constraints.append newline
			}
		}

		constraints.length() ? "  static constraints = {$newline$constraints  }" : ''
	}

	void findNewProperties() {

		def idProperty = getIdentifierProperty()
		def versionProperty = getVersionProperty()

		super.getAllPropertiesIterator().each {
			if (it == versionProperty || it == idProperty) {
				return
			}

			if (it.value instanceof ManyToOne) {
				newProperties << it
			}
		}
		newProperties = newProperties.sort { it.name }
	}

	String renderMany() {

		def belongs = new TreeSet()
		def hasMany = new TreeSet()
		findBelongsToAndHasMany belongs, hasMany

		if (belongs || hasMany) {
			def many = new StringBuilder()
			if (hasMany) {
				many.append combine('static hasMany = [', ', ', ']', hasMany, true)
				many.append newline
			}
			if (belongs) {
				many.append newline
				many.append combine('static belongsTo = [', ', ', ']', belongs)
				many.append newline
			}
			return many.toString()
		}

		''
	}

	String renderMappedBy() {
		def belongs = new TreeSet()
		def hasMany = new TreeSet()
		findBelongsToAndHasMany belongs, hasMany

		if (!hasMany) {
			return ''
		}

		def grouped = [:]
		for (many in hasMany) {
			String[] parts = many.split(':')
			String className = parts[1].trim()
			def propNames = grouped[className]
			if (!propNames) {
				propNames = []
				grouped[className] = propNames
			}
			propNames << parts[0].trim()
		}

		def mappedBy = new TreeSet()
		Set classNames = []
		grouped.each { className, propNames ->
			if (propNames.size() > 1) {
				for (propName in propNames) {
					mappedBy << propName + ': "TODO"'
				}
				classNames << className
			}
		}

		if (!classNames) {
			return ''
		}

		"  // TODO you have multiple hasMany references for class(es) $classNames " + newline +
		"  //      so you'll need to disambiguate them with the 'mappedBy' property:" + newline +
		combine('static mappedBy = [', ', ', ']', mappedBy, true) + newline
	}

	private void findBelongsToAndHasMany(Set belongs, Set hasMany) {

		def revengConfig = grailsConfig.grails.plugin.reveng
		boolean bidirectionalManyToOne = revengConfig.bidirectionalManyToOne instanceof Boolean ?
				revengConfig.bidirectionalManyToOne : true
		boolean mapManyToManyJoinTable = revengConfig.mapManyToManyJoinTable instanceof Boolean ?
				revengConfig.mapManyToManyJoinTable : false

		def idProperty = getIdentifierProperty()
		def versionProperty = getVersionProperty()

		def strategy = GrailsReverseEngineeringStrategy.INSTANCE
		super.getAllPropertiesIterator().each { prop ->
			if (prop == versionProperty || prop == idProperty) {
				return
			}

			if (bidirectionalManyToOne && prop.value instanceof ManyToOne) {
				if (!isPartOfPrimaryKey(prop)) {
					belongs << classShortName(prop.value.referencedEntityName)
				}
			}

			if (prop.value instanceof org.hibernate.mapping.Set) {
				boolean isManyToMany = strategy.isManyToManyTable(prop.value.collectionTable)
				boolean isReallyManyToMany = strategy.isReallyManyToManyTable(prop.value.collectionTable)
				if ((bidirectionalManyToOne && !isManyToMany && !isReallyManyToMany) || isManyToMany) {
					String classShortName = classShortName(prop.value.element.type.name)
					hasMany << "$prop.name: $classShortName"
					if (isManyToMany) {
						if (strategy.isManyToManyBelongsTo(prop.value.collectionTable, prop.persistentClass.table)) {
							belongs << findManyToManyOtherSide(prop)
						}
					}
				}
			}
		}

		belongs.remove classShortName(getMappedClassName())
	}

	private boolean isPartOfPrimaryKey(Property prop) {
		if (c2j.isComponent(getIdentifierProperty()) &&
					GrailsReverseEngineeringStrategy.INSTANCE.isReallyManyToManyTable(clazz.table)) {
			String propClassShortName = classShortName(prop.value.referencedEntityName)
			for (newProp in newProperties) {
				if (classShortName(newProp.value.referencedEntityName) == propClassShortName) {
					return true
				}
			}
		}

		false
	}

	private String findRealIdName(Property prop) {
		if (c2j.isComponent(getIdentifierProperty()) &&
					GrailsReverseEngineeringStrategy.INSTANCE.isReallyManyToManyTable(clazz.table)) {
					
			for (newProp in newProperties) {
				if (newProp.name + 'Id' == prop.name) {
					return newProp.name
				}
			}
		}

		prop.name
	}

	String renderProperties() {
		def props = new StringBuilder()

		def propIt = getAllPropertiesIterator()
		if (propIt.hasNext()) {
			props.append '  // field(s)' + newline
		}
		propIt.each { prop ->
			if (getMetaAttribAsBool(prop, 'gen-property', true)) {
				if (findRealIdName(prop) == prop.name) {
					props.append '  '
					props.append getJavaTypeName(prop, true)
					props.append ' '
					props.append prop.name
					props.append newline
				}
			}
		}

		if (newProperties.size() > 0) {
			props.append '  // relation(s)' + newline
		}
		for (prop in newProperties) {
			props.append '  '
			props.append classShortName(prop.value.referencedEntityName)
			props.append ' '
			props.append prop.name
			props.append newline
		}
		props.toString()
	}

	String renderMapping() {
		def mapping = new StringBuilder()
		mapping.append renderId()
		mapping.append renderVersion()
		mapping.append renderTable()
		mapping.length() ? "  static mapping = {$newline$mapping  }" : ''
	}

	String renderClassStart() {
		"class ${getDeclarationName()}${renderImplements()}{"
	}
	
	String renderImplements() {
		getIdentifierProperty().columnSpan > 1 ? ' implements Serializable ' : ' '
	}

	String getJavaTypeName(Property p, boolean useGenerics) {
		String name = super.getJavaTypeName(p, useGenerics)
		typeNameReplacements[name] ?: name
	}

	private String findManyToManyOtherSide(Property prop) {
		String belongsTo
		prop.value.collectionTable.foreignKeyIterator.each { ForeignKey fk ->
			if (prop.value.table != fk.referencedTable) {
				belongsTo = classShortName(fk.referencedEntityName)
			}
		}
		belongsTo
	}

	private String classShortName(String className) {
		if (className.indexOf('.') > -1) {
			return className.substring(className.lastIndexOf('.') + 1)
		}
		className
	}

	private String combine(String start, String delim, String end, things, boolean lineUp = false) {
		def buffer = new StringBuilder('  ')

		String pad
		if (lineUp) {
			def bufferPad = new StringBuilder()
			bufferPad.append newline
			bufferPad.append '  '
			start.length().times { bufferPad.append ' ' }
			pad = bufferPad.toString()
		}
		
		buffer.append start
		String delimiter = ''
		things.each {
			buffer.append delimiter
			buffer.append it
			delimiter = lineUp ? delim.trim() + pad : delim
		}
		buffer.append end
		buffer.toString()
	}
}
