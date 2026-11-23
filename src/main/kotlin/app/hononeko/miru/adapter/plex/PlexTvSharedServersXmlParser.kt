package app.hononeko.miru.adapter.plex

import app.hononeko.miru.adapter.MediaUserLibraryAccess
import org.w3c.dom.Element
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import java.io.IOException
import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.parsers.ParserConfigurationException

/**
 * Parses Plex.tv XML responses from `GET https://plex.tv/api/servers/{machineId}/shared_servers`
 * with XXE/DTD disabled.
 */
internal object PlexTvSharedServersXmlParser {
    private fun secureDocumentBuilderFactory(): DocumentBuilderFactory =
        DocumentBuilderFactory.newInstance().apply {
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            isXIncludeAware = false
            isExpandEntityReferences = false
        }

    fun parse(xml: String): List<MediaUserLibraryAccess> {
        val document =
            try {
                val builder = secureDocumentBuilderFactory().newDocumentBuilder()
                builder.parse(InputSource(StringReader(xml)))
            } catch (e: SAXException) {
                throw IllegalArgumentException("Invalid Plex.tv shared servers XML payload", e)
            } catch (e: IOException) {
                throw IllegalArgumentException("Invalid Plex.tv shared servers XML payload", e)
            } catch (e: ParserConfigurationException) {
                throw IllegalArgumentException("Invalid Plex.tv shared servers XML payload", e)
            }

        val upperNodes = document.getElementsByTagName("SharedServer")
        val nodes = if (upperNodes.length > 0) upperNodes else document.getElementsByTagName("sharedServer")
        if (nodes.length == 0) return emptyList()

        val mergedByUser = linkedMapOf<String, MediaUserLibraryAccess>()
        for (i in 0 until nodes.length) {
            val element = nodes.item(i) as? Element ?: continue
            val entry = parseSharedServerElement(element) ?: continue
            val existing = mergedByUser[entry.externalUserId]
            mergedByUser[entry.externalUserId] =
                if (existing == null) {
                    entry
                } else {
                    val combinedAll = existing.allLibraries || entry.allLibraries
                    MediaUserLibraryAccess(
                        externalUserId = entry.externalUserId,
                        allLibraries = combinedAll,
                        sectionKeys = if (combinedAll) emptySet() else existing.sectionKeys + entry.sectionKeys,
                    )
                }
        }
        return mergedByUser.values.toList()
    }

    private fun parseSharedServerElement(element: Element): MediaUserLibraryAccess? {
        val rawUserId = element.attrOrNull("userID") ?: element.attrOrNull("userId") ?: return null
        val numericUserId = rawUserId.toLongOrNull()
        if (numericUserId != null && numericUserId <= 0L) return null

        val sharedAttr = element.attrOrNull("shared")
        if (sharedAttr != null && !isTruthy(sharedAttr)) {
            return null
        }

        val allLibraries = isTruthy(element.attrOrNull("allLibraries")) || isTruthy(element.attrOrNull("owned"))
        val sectionKeys =
            if (allLibraries) {
                emptySet()
            } else {
                parseSharedSectionKeys(element)
            }

        return MediaUserLibraryAccess(
            externalUserId = rawUserId,
            allLibraries = allLibraries,
            sectionKeys = sectionKeys,
        )
    }

    private fun parseSharedSectionKeys(sharedServerElement: Element): Set<String> {
        val sectionNodes =
            sharedServerElement.getElementsByTagName("Section").let { upper ->
                if (upper.length > 0) upper else sharedServerElement.getElementsByTagName("section")
            }
        if (sectionNodes.length == 0) return emptySet()

        val keys = linkedSetOf<String>()
        for (i in 0 until sectionNodes.length) {
            val section = sectionNodes.item(i) as? Element ?: continue
            if (!isTruthy(section.attrOrNull("shared"))) continue
            val key = section.attrOrNull("key") ?: continue
            keys += key
        }
        return keys
    }

    private fun Element.attrOrNull(name: String): String? =
        getAttribute(name).trim().takeIf { it.isNotEmpty() }

    private fun isTruthy(raw: String?): Boolean =
        raw == "1" || raw.equals("true", ignoreCase = true)
}
