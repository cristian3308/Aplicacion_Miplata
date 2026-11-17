package com.example.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * DAO reactivo de Room para todas las operaciones locales de MiPlata (v6).
 * Cada dato vive en un único lugar: presupuestos en categories.monthlyLimit,
 * obligaciones fijas en transactions (PAGO_PROGRAMADO recurrente) y deudas en accounts (PRESTAMO).
 */
@Dao
interface MiPlataDao {

    // --- CUENTAS ---
    @Query("SELECT * FROM accounts ORDER BY id ASC")
    fun observeAccounts(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM accounts ORDER BY id ASC")
    suspend fun getAccountsOnce(): List<AccountEntity>

    @Query("SELECT * FROM accounts WHERE id = :id LIMIT 1")
    suspend fun getAccountById(id: Long): AccountEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAccount(account: AccountEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAccounts(accounts: List<AccountEntity>)

    @Update
    suspend fun updateAccount(account: AccountEntity)

    @Delete
    suspend fun deleteAccount(account: AccountEntity)

    @Query("SELECT COUNT(*) FROM transactions WHERE accountId = :accountId OR toAccountId = :accountId")
    suspend fun countTransactionsForAccount(accountId: Long): Int

    // --- CATEGORÍAS ---
    @Query("SELECT * FROM categories ORDER BY groupName ASC, id ASC")
    fun observeCategories(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories ORDER BY groupName ASC, id ASC")
    suspend fun getCategoriesOnce(): List<CategoryEntity>

    @Query("SELECT * FROM categories WHERE id = :id LIMIT 1")
    suspend fun getCategoryById(id: Long): CategoryEntity?

    @Query("SELECT * FROM categories WHERE systemKey = :systemKey LIMIT 1")
    suspend fun getCategoryBySystemKey(systemKey: String): CategoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCategory(category: CategoryEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCategories(categories: List<CategoryEntity>)

    @Update
    suspend fun updateCategory(category: CategoryEntity)

    @Delete
    suspend fun deleteCategory(category: CategoryEntity)

    @Query("SELECT COUNT(*) FROM transactions WHERE categoryId = :categoryId")
    suspend fun countTransactionsForCategory(categoryId: Long): Int

    @Query("UPDATE transactions SET categoryId = :newCategoryId WHERE categoryId = :oldCategoryId")
    suspend fun reassignTransactionsCategory(oldCategoryId: Long, newCategoryId: Long)

    // --- MOVIMIENTOS / TRANSACCIONES ---
    @Query("SELECT * FROM transactions ORDER BY timestamp DESC, id DESC")
    fun observeTransactions(): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions ORDER BY timestamp DESC, id DESC")
    suspend fun getTransactionsOnce(): List<TransactionEntity>

    @Query("SELECT * FROM transactions WHERE id = :id LIMIT 1")
    suspend fun getTransactionById(id: Long): TransactionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTransaction(transaction: TransactionEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTransactions(transactions: List<TransactionEntity>)

    @Update
    suspend fun updateTransaction(transaction: TransactionEntity)

    @Delete
    suspend fun deleteTransaction(transaction: TransactionEntity)

    @Query("DELETE FROM transactions WHERE id = :id")
    suspend fun deleteTransactionById(id: Long)

    // --- METAS DE AHORRO ---
    @Query("SELECT * FROM saving_goals ORDER BY deadlineTimestamp ASC")
    fun observeSavingGoals(): Flow<List<SavingGoalEntity>>

    @Query("SELECT * FROM saving_goals ORDER BY deadlineTimestamp ASC")
    suspend fun getSavingGoalsOnce(): List<SavingGoalEntity>

    @Query("SELECT * FROM saving_goals WHERE id = :id LIMIT 1")
    suspend fun getSavingGoalById(id: Long): SavingGoalEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSavingGoal(goal: SavingGoalEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSavingGoals(goals: List<SavingGoalEntity>)

    @Update
    suspend fun updateSavingGoal(goal: SavingGoalEntity)

    @Delete
    suspend fun deleteSavingGoal(goal: SavingGoalEntity)

    // --- LIMPIEZA DE DEMO Y RESTAURACIÓN ---
    @Query("DELETE FROM transactions WHERE isDemo = 1")
    suspend fun deleteDemoTransactions()

    @Query("DELETE FROM saving_goals WHERE isDemo = 1")
    suspend fun deleteDemoGoals()

    @Query("DELETE FROM accounts WHERE isDemo = 1")
    suspend fun deleteDemoAccounts()

    @Query("DELETE FROM transactions")
    suspend fun clearAllTransactions()

    @Query("DELETE FROM saving_goals")
    suspend fun clearAllSavingGoals()

    @Query("DELETE FROM accounts")
    suspend fun clearAllAccounts()

    @Query("DELETE FROM categories")
    suspend fun clearAllCategories()
}
