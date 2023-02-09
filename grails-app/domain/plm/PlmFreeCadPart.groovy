package plm


import grails.compiler.GrailsCompileStatic
import groovy.transform.CompileStatic
import org.taack.User
import taack.ast.annotation.TaackFieldEnum
import taack.domain.IDomainHistory
import taack.domain.IEnumTransition2

@CompileStatic
enum PlmFreeCadPartStatus implements IEnumTransition2<User>  {
    CREATED, FREE, LOCKED, FREEZE, OBSOLETE

    @Override
    IEnumTransition2[] transitionsTo(User user) {
        switch (this) {
            case CREATED:
                return [FREE, LOCKED, FREEZE, OBSOLETE] as IEnumTransition2[]
            case LOCKED:
                return [FREE, FREEZE] as IEnumTransition2[]
            case FREEZE:
                return [FREE, LOCKED] as IEnumTransition2[]
            case OBSOLETE:
                return [FREE, FREEZE, LOCKED] as IEnumTransition2[]
        }
    }

    @Override
    List<String> getLockedFields(User user) {
        return null
    }
}

@GrailsCompileStatic
@TaackFieldEnum
class PlmFreeCadPart implements IDomainHistory<PlmFreeCadPart> {
    Date dateCreated
    Date lastUpdated

    User userCreated
    User userUpdated

    User lockedBy

    String plmFilePath
    String plmContentType
    String plmContentShaOne
    String originalName
    String label
    String pathOnHost
    String fileId

    String comment
    String commentVersion

    Set<PlmFreeCadLink> plmLinks
    Set<PlmFreeCadLink> plmLinksOld

    Long creationOrder = Long.MAX_VALUE
    Boolean active = true
    PlmFreeCadPart nextVersion
//    String historyComputed
//    Long previousVersionComputed

    PlmFreeCadPartStatus status = PlmFreeCadPartStatus.CREATED

    static constraints = {
        lockedBy nullable: true
        comment nullable: true
        commentVersion nullable: true, widget: "markdown"
        nextVersion nullable: true
        plmContentType nullable: true
        plmContentShaOne nullable: true
        originalName nullable: true
        pathOnHost nullable: true
    }

    static hasMany = [
            plmLinks: PlmFreeCadLink,
            plmLinksOld: PlmFreeCadLink
    ]

    static mapping = {
        comment type: 'text'
        commentVersion type: 'text'
//        WORKS ONLY WITH POSTGRESQL !!!
//        historyComputed formula: '(SELECT array(SELECT t.id FROM plm_free_cad_part t WHERE t.next_version_id = id or t.id = id order by t.creation_order))'
//        previousVersionComputed formula: '(' +
//                'select ' +
//                '   case\n' +
//                '      when creation_order = 1 then next_version_id\n' +
//                '      when (select ti2.id from plm_free_cad_part ti2 where ti2.creation_order < creation_order and ti2.next_version_id = next_version_id order by ti2.creation_order desc limit 1) is not null then (select ti2.id from plm_free_cad_part ti2 where ti2.creation_order < creation_order and ti2.next_version_id = next_version_id order by ti2.creation_order desc limit 1)\n' +
//                '      else next_version_id' +
//                '   end)\n'
    }

    static mappedBy = [plmLinks: "parentPart", plmLinksOld: "none"]

    @Override
    PlmFreeCadPart cloneDirectObjectData() {
        if (this.id) {
            PlmFreeCadPart oldPart = new PlmFreeCadPart()
            oldPart.userCreated = userUpdated
            log.info "PlmFreeCadPart::cloneDirectObjectData ${version} ${userCreated}: ${dateCreated}, ${userUpdated}: ${lastUpdated}"
            oldPart.creationOrder = version + 1
            oldPart.active = false
            oldPart.nextVersion = this
            oldPart.lockedBy = lockedBy
            oldPart.plmFilePath = plmFilePath
            oldPart.plmContentType = plmContentType
            oldPart.plmContentShaOne = plmContentShaOne
            oldPart.originalName = originalName
            oldPart.pathOnHost = pathOnHost
            oldPart.fileId = fileId
            oldPart.comment = comment
            oldPart.label = label
            oldPart.commentVersion = commentVersion
            oldPart.status = status
            plmLinks.each {
                oldPart.addToPlmLinksOld(it)
            }
            return oldPart
        }
        return null
    }

    @Override
    List<PlmFreeCadPart> getHistory() {
//        return historyComputed?.replaceAll("[{}\"]", "")?.split(',')?.collect { read(it) }
        try {
            return (PlmFreeCadPart.findAllByNextVersion(this) + this).sort {
                (it as PlmFreeCadPart).creationOrder
            }
        } catch(e) {
            println "PlmFreeCadPart ${e}"
            return null
        }
    }

    PlmFreeCadPart getPreviousVersion() {
        //read(previousVersionComputed)
        if (creationOrder == 1) return nextVersion
        else {
            def t = PlmFreeCadPart.find("from PlmFreeCadPart t where creationOrder < :thisCreationOrder and nextVersion.id = :thisNextVersionId order by creationOrder desc", [thisCreationOrder: creationOrder, thisNextVersionId: nextVersion?.id ?: id])
            if (t) return t
            else return nextVersion
        }
    }

    User getHistoryUserCreated() {
        if (!nextVersion) userUpdated
        else userCreated
    }

    Date getHistoryDateCreated() {
        if (!nextVersion) lastUpdated
        else {
            previousVersion?.dateCreated
        }
    }

    private Collection<PlmFreeCadPart> linkedPartsFromParts(Collection<PlmFreeCadPart> parts) {
        Set ret = []
        ret.addAll(parts)
        parts.each {
           ret.addAll(linkedPartsFromParts(it.linkedParts))
        }
        ret
    }

    Collection<PlmFreeCadPart> getLinkedParts() {
        return this.plmLinks*.part
    }

    Collection<PlmFreeCadPart> getAllLinkedParts() {
        linkedPartsFromParts([this])
    }
}
