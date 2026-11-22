package app.hononeko.miru.adapter.plex

import org.w3c.dom.Element
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import java.io.IOException
import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.parsers.ParserConfigurationException

/**
 * Parses Plex.tv legacy XML responses from `GET https://plex.tv/api/users` with XXE/DTD disabled.
 */
internal object PlexTvUsersXmlParser {
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

    fun parse(xml: String): List<PlexTvFriendDto> {
        val document =
            try {
                val builder = secureDocumentBuilderFactory().newDocumentBuilder()
                builder.parse(InputSource(StringReader(xml)))
            } catch (e: SAXException) {
                throw IllegalArgumentException("Invalid Plex.tv users XML payload", e)
            } catch (e: IOException) {
                throw IllegalArgumentException("Invalid Plex.tv users XML payload", e)
            } catch (e: ParserConfigurationException) {
                throw IllegalArgumentException("Invalid Plex.tv users XML payload", e)
            }

        val upperNodes = document.getElementsByTagName("User")
        val nodes = if (upperNodes.length > 0) upperNodes else document.getElementsByTagName("user")
        if (nodes.length == 0) return emptyList()

        val friends = ArrayList<PlexTvFriendDto>(nodes.length)
        for (i in 0 until nodes.length) {
            val element = nodes.item(i) as? Element ?: continue
            val id = element.getAttribute("id").trim().toLongOrNull() ?: continue
            if (id <= 0L) continue

            val username = element.attrOrNull("username")
            val title = element.attrOrNull("title")
            val guest = element.attrOrNull("guest")
            val isGuest = guest == "1" || guest.equals("true", ignoreCase = true)
            if (isGuest && username == null && title == null) continue

            friends +=
                PlexTvFriendDto(
                    id = id,
                    uuid = element.attrOrNull("uuid"),
                    username = username,
                    title = title,
                    email = element.attrOrNull("email"),
                    thumb = element.attrOrNull("thumb"),
                )
        }
        return friends
    }

    private fun Element.attrOrNull(name: String): String? =
        getAttribute(name).trim().takeIf { it.isNotEmpty() }
}
