package com.codex.agent.data.repository

import androidx.room.withTransaction
import com.codex.agent.data.db.AppDatabase
import com.codex.agent.data.entity.WorkspaceEntity
import com.codex.agent.data.model.Constants
import kotlinx.coroutines.flow.Flow

/**
 * Repositório de workspaces (projetos). A criação de diretórios/git/AGENT.md
 * no disco é responsabilidade do orquestrador; aqui ficam apenas os dados.
 */
class WorkspaceRepository(private val db: AppDatabase) {

    private val dao get() = db.workspaceDao()

    fun observeAll(): Flow<List<WorkspaceEntity>> = dao.observeAll()

    fun observeActive(): Flow<WorkspaceEntity?> = dao.observeActive()

    suspend fun byId(id: Long): WorkspaceEntity? = dao.getById(id)

    suspend fun active(): WorkspaceEntity? = dao.getActive()

    suspend fun create(name: String, rootPath: String): WorkspaceEntity {
        val clean = rootPath.trim().trimEnd('/')
        require(clean.startsWith("/")) { "Workspace precisa de caminho absoluto: $rootPath" }
        dao.getByRootPath(clean)?.let { existing ->
            dao.touch(existing.id, System.currentTimeMillis())
            return existing
        }
        val entity = WorkspaceEntity(
            name = name.ifBlank { clean.substringAfterLast('/') },
            rootPath = clean
        )
        val id = dao.insert(entity)
        return entity.copy(id = id)
    }

    suspend fun setActive(id: Long) {
        db.withTransaction {
            dao.clearActive()
            dao.markActive(id)
            dao.touch(id, System.currentTimeMillis())
        }
    }

    suspend fun touch(id: Long) = dao.touch(id, System.currentTimeMillis())

    suspend fun delete(id: Long) = dao.deleteById(id)

    /**
     * Garante que o workspace padrão (/sdcard/CodexProjects) exista e seja o ativo.
     * Idempotente; chamado no boot da Application.
     */
    suspend fun ensureDefaultWorkspace(): WorkspaceEntity {
        val root = Constants.DEFAULT_WORKSPACE_ROOT
        val existing = dao.getByRootPath(root)
        if (existing != null) {
            if (dao.getActive() == null) {
                setActive(existing.id)
            }
            return existing
        }
        val created = create("CodexProjects", root)
        if (dao.getActive() == null) {
            setActive(created.id)
        }
        return created
    }
}
