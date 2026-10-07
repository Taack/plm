package plm

import attachment.Term
import crew.User
import grails.compiler.GrailsCompileStatic
import grails.converters.JSON
import grails.plugin.springsecurity.SpringSecurityService
import grails.plugin.springsecurity.annotation.Secured
import grails.util.Pair
import org.springframework.beans.factory.annotation.Value
import taack.domain.TaackFilter
import taack.domain.TaackFilterService

@GrailsCompileStatic
@Secured(["ROLE_PLM_USER", "ROLE_ADMIN"])
class PlmJsonController {

    PlmFreeCadSecurityService plmFreeCadSecurityService
    SpringSecurityService springSecurityService
    TaackFilterService taackFilterService

    @Value('${grails.controllers.upload.maxFileSize}')
    Long maximumFileUploadSize

    @Value('${grails.controllers.upload.maxRequestSize}')
    Long maximumRequestSize

    private List<Map<String, Object>> prepareParts(List<PlmFreeCadPart> parts, User user) {
        parts.grep { PlmFreeCadPart part ->
            plmFreeCadSecurityService.canDownloadFile(part, user)
        }.collect { PlmFreeCadPart part ->
                [
                        id          : part.id,
                        label       : part.label,
                        originalName: part.originalName
                ] as Map<String, Object>
        }
    }

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
        String buildDate = properties.getProperty("server.build.date", "unknown")

        if (buildDate != "unknown") {
            return buildDate.split(" ")[0]
        }

        return buildDate
    }

    def queryModel() {
        PlmFreeCadPart part = new PlmFreeCadPart()
        Pair<List<PlmFreeCadPart>, Long> parts = taackFilterService.getBuilder(PlmFreeCadPart)
                    .setSortOrder(TaackFilter.Order.ASC, part.label_)
                    .build().list() as Pair<List<PlmFreeCadPart>, Long>
        response.contentType = 'application/json'
        render prepareParts(parts.aValue, springSecurityService.currentUser as User) as JSON
    }

    // Endpoint: /plmJson/serverInfo
    def serverInfo() {

        Map<String, Object> result = [
                serverBuild             : getServerBuildDate(),
                messagingProtocolVersion: "2",
                maximumFileUploadSize   : maximumFileUploadSize,
                maxRequestSize          : maximumRequestSize
        ] as Map<String, Object>

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
            ] as Map<String, Object>
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
                    error: 'Tag not found',
                    tagId: tagId
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

        response.contentType = 'application/json'
        render prepareParts(parts, springSecurityService.currentUser as User) as JSON
    }

    //create endpoint for getting status of PLM for parts, exposes /plmJson/workspaceParts
    @Secured(["ROLE_PLM_USER"])
    def workspaceParts() {
    
        Map<String, Object> requestJson = request.JSON as Map<String, Object>
    
        List<Map<String, Object>> workspaceParts =
                (requestJson.get("parts") ?: []) as List<Map<String, Object>>
    
        User user = springSecurityService.currentUser as User
    
        List<Map<String, Object>> resultParts = []
    
        workspaceParts.each { Map<String, Object> workspacePart ->
    
            String name = workspacePart.get("name") as String
            String relativePath = workspacePart.get("relativePath") as String
    
            if (!name) {
                return
            }
    
            List<PlmFreeCadPart> matchingParts = PlmFreeCadPart.executeQuery(
                    '''
                    select p
                    from PlmFreeCadPart p
                    where lower(p.originalName) = lower(:originalName)
                      and p.active = true
                      and p.nextVersion is null
                      and p.pathOnHost like '%FCStd'
                    order by p.id desc
                    ''',
                    [originalName: name],
                    [max: 1]
            ) as List<PlmFreeCadPart>
    
            PlmFreeCadPart plmPart =
                    matchingParts ? matchingParts[0] : null
    
            Map<String, Object> resultPart = [
                    name        : name,
                    relativePath: relativePath
            ] as Map<String, Object>
    
            if (plmPart &&
                    plmFreeCadSecurityService.canDownloadFile(plmPart, user)) {
    
                resultPart.put("existsInPlm", true)
                resultPart.put("plmPartId", plmPart.id)
                resultPart.put("latestVersion", plmPart.computedVersion)
                resultPart.put("plmStatus", "CURRENT")
    
            } else {
    
                resultPart.put("existsInPlm", false)
                resultPart.put("plmStatus", "NOT_IN_PLM")
            }
    
            resultParts.add(resultPart)
        }
    
        response.contentType = 'application/json'
    
        render([
                parts: resultParts
        ] as JSON)
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

        response.contentType = 'application/json'
        render prepareParts(parts, springSecurityService.currentUser as User) as JSON
    }
}
