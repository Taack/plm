package plm

import attachment.Term
import grails.converters.JSON
import grails.plugin.springsecurity.annotation.Secured

@Secured(["ROLE_PLM_USER", "ROLE_ADMIN"])

class PlmJsonController {
    private String getServerBuildDate() {
    Properties properties = new Properties()

    InputStream input = this.class.classLoader
            .getResourceAsStream("build-info.properties")

    if (input) {
        try {
            properties.load(input)
        } finally {
            input.close()
        }
    }

    return properties.getProperty("server.build.date", "unknown")
}
    // Endpoint: /plmJson/serverInfo
    def serverInfo() {
    
        long maximumFileUploadSize =
                grailsApplication.config.grails.controllers.upload.maxFileSize as Long
    
        Map<String, Object> result = [
            serverVersion            : getServerBuildDate(),
            messagingProtocolVersion : "1.0",
            maximumFileUploadSize    : maximumFileUploadSize
        ]
    
        response.contentType = 'application/json'
        render result as JSON
    }
    //expose api for tags, endpoint /plmJson/tags
    def tags() {
        List<Term> termList = Term.list(
                sort: 'name',
                order: 'asc'
        ) as List<Term>

        List<Map<String, Object>> result = termList.collect { Term tag ->
            [
                id    : tag.id,
                name  : tag.name,
                parent: tag.parent?.name
            ]
        }

        response.contentType = 'application/json'
        render result as JSON
    }

    //Create end point  /plmJson/partsByTag?tagId= 
    def partsByTag(Long tagId) {
        if (!tagId) {
            response.status = 400
            render([error: 'tagId is required'] as JSON)
            return
        }

        Term tag = Term.get(tagId)

        if (!tag) {
            response.status = 404
            render([
                error : 'Tag not found',
                tagId : tagId
            ] as JSON)
            return
        }

        List<PlmFreeCadPart> parts = PlmFreeCadPart.executeQuery(
            '''
            select distinct p
            from PlmFreeCadPart p
            where p.active = true
              and p.nextVersion is null
              and p.pathOnHost like '%FCStd'
              and exists (
                  select 1
                  from DocumentCategory dc
                  join dc.tags t
                  where dc.id = p.documentCategory.id
                    and t.id = :tagId
              )
            order by p.label
            ''',
            [tagId: tagId]
        ) as List<PlmFreeCadPart>

        List<Map<String, Object>> result = parts.collect {
            PlmFreeCadPart part ->
                [
                    id          : part.id,
                    label       : part.label,
                    originalName: part.originalName
                ]
        }

        response.contentType = 'application/json'
        render result as JSON
    }

    //create endpoint for searching for parts, exposes /plmJson/searchParts?originalName=
    def searchParts(String originalName) {
        if (!originalName?.trim()) {
            response.status = 400
            render([
                error: 'originalName is required'
            ] as JSON)
            return
        }

        String searchText = originalName.trim()

        List<PlmFreeCadPart> parts = PlmFreeCadPart.executeQuery(
                '''
                select distinct p
                from PlmFreeCadPart p
                where lower(p.originalName) like lower(:searchText)
                  and p.active = true
                  and p.nextVersion is null
                  and p.pathOnHost like '%FCStd'
                order by p.originalName
                ''',
                [
                    searchText: '%' + searchText + '%'
                ]
        ) as List<PlmFreeCadPart>

        List<Map<String, Object>> result = parts.collect {
            PlmFreeCadPart part ->
                [
                    id          : part.id,
                    label       : part.label,
                    originalName: part.originalName
                ]
        }

        response.contentType = 'application/json'
        render result as JSON
    }
}
