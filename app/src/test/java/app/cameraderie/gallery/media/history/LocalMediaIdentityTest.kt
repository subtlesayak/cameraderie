package app.cameraderie.gallery.media.history

import org.junit.Assert.*
import org.junit.Test

class LocalMediaIdentityTest {
    @Test fun collectionAliasesResolveToOneHistoryIdentity() {
        val expected="content://media/external_primary/file/42"
        for(path in listOf("file/42","images/media/42","video/media/42","downloads/42"))assertEquals(expected,LocalMediaIdentity.key("content://media/external_primary/$path"))
    }
    @Test fun unrelatedProvidersVolumesAndQueriesAreNotLocalImportIdentities() {
        for(value in listOf("content://other/external_primary/file/42","content://media/external/file/42","content://media/external_primary/file/42?token=secret","content://media/external_primary/file/42#other","file:///photo.jpg")) {
            try { LocalMediaIdentity.key(value);fail(value) } catch(_:IllegalArgumentException) {}
        }
    }
    @Test fun malformedAndNonpositiveRowsCannotCollideWithValidMedia() {
        for(path in listOf("file/0","file/-1","file/+42","file/042","file/%34%32","file/name","images/42","images/media/42/other")) {
            try { LocalMediaIdentity.key("content://media/external_primary/$path");fail(path) } catch(_:IllegalArgumentException) {}
        }
    }
}
