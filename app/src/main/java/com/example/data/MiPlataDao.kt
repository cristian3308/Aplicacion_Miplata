package com.example.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * DAO reactivo de Room para todas las operaciones locales de MiPlata (v2).
 */
@Dao
interface MiPlataDao {

    // --- CUENTAS ---
    @Query("SELECT * FROM accounts ORDER BY id ASC")
    fun observeAccounts(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM accounts ORDER BY id ASC")
    suspend fun getAccountsOnce(): List<AccountEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAccount(account: AccountEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAccounts(accounts: List<AccountEntity>)

    @Update
    suspend fun updateAccount(account: AccountEntity)

    @Delete
    suspend fun deleteAccount(account: AccountEntity)

    // --- CATEGORÍAS ---
    @Query("SELECT * FROM categories ORDER BY groupName ASC, id ASC")
    fun observeCategories(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories ORDER BY groupName ASC, id ASC")
    suspend fun getCategoriesOnce(): List<CategoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCategory(category: CategoryEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCategories(categories: List<CategoryEntity>)

    @Update
    suspend fun updateCategory(category: CategoryEntity)

    // --- MOVIMIENTOS / TRANSACCIONES ---
    @Query("SELECT * FROM transactions ORDER BY timestamp DESC, id DESC")
    fun observeTransactions(): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions ORDER BY timestamp DESC, id DESC")
    suspend fun getTransactionsOnce(): List<TransactionEntity>

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

    // --- PRESUPUESTOS ---
    @Query("SELECT * FROM budgets ORDER BY id ASC")
    fun observeBudgets(): Flow<List<BudgetEntity>>

    @Query("SELECT * FROM budgets ORDER BY id ASC")
    suspend fun getBudgetsOnce(): List<BudgetEntity>

    @Query("SELECT * FROM budgets WHERE categoryId = :categoryId LIMIT 1")
    suspend fun getBudgetForCategory(categoryId: Long): BudgetEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBudget(budget: BudgetEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBudgets(budgets: List<BudgetEntity>)

    @Delete
    suspend fun deleteBudget(budget: BudgetEntity)

    // --- METAS DE AHORRO ---
    @Query("SELECT * FROM saving_goals ORDER BY deadlineTimestamp ASC")
    fun observeSavingGoals(): Flow<List<SavingGoalEntity>>

    @Query("SELECT * FROM saving_goals ORDER BY deadlineTimestamp ASC")
    suspend fun getSavingGoalsOnce(): List<SavingGoalEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSavingGoal(goal: SavingGoalEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSavingGoals(goals: List<SavingGoalEntity>)

    @Update
    suspend fun updateSavingGoal(goal: SavingGoalEntity)

    @Delete
    suspend fun deleteSavingGoal(goal: SavingGoalEntity)

    // --- OBLIGACIONES FIJAS ---
    @Query("SELECT * FROM recurring_obligations ORDER BY dayOfMonth ASC")
    fun observeObligations(): Flow<List<RecurringObligationEntity>>

    @Query("SELECT * FROM recurring_obligations ORDER BY dayOfMonth ASC")
    suspend fun getObligationsOnce(): List<RecurringObligationEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertObligation(obligation: RecurringObligationEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertObligations(obligations: List<RecurringObligationEntity>)

    @Update
    suspend fun updateObligation(obligation: RecurringObligationEntity)

    @Delete
    suspend fun deleteObligation(obligation: RecurringObligationEntity)

    // --- DEUDAS ---
    @Query("SELECT * FROM debts ORDER BY remainingBalance DESC")
    fun observeDebts(): Flow<List<DebtEntity>>

    @Query("SELECT * FROM debts ORDER BY remainingBalance DESC")
    suspend fun getDebtsOnce(): List<DebtEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDebt(debt: DebtEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDebts(debts: List<DebtEntity>)

    @Update
    suspend fun updateDebt(debt: DebtEntity)

    @Delete
    suspend fun deleteDebt(debt: DebtEntity)

    // --- LIMPIEZA DE DEMO Y RESTAURACIÓN ---
    @Query("DELETE FROM transactions WHERE isDemo = 1")
    suspend fun deleteDemoTransactions()

    @Query("DELETE FROM budgets WHERE isDemo = 1")
    suspend fun deleteDemoBudgets()

    @Query("DELETE FROM saving_goals WHERE isDemo = 1")
    suspend fun deleteDemoGoals()

    @Query("DELETE FROM recurring_obligations WHERE isDemo = 1")
    suspend fun deleteDemoObligations()

    @Query("DELETE FROM debts WHERE isDemo = 1")
    suspend fun deleteDemoDebts()

    @Query("DELETE FROM accounts WHERE isDefault = 0 AND initialBalance = 0")
    suspend fun deleteNonDefaultDemoAccounts()

    @Query("DELETE FROM transactions")
    suspend fun clearAllTransactions()

    @Query("DELETE FROM budgets")
    suspend fun clearAllBudgets()

    @Query("DELETE FROM saving_goals")
    suspend fun clearAllSavingGoals()

    @Query("DELETE FROM recurring_obligations")
    suspend fun clearAllObligations()

    @Query("DELETE FROM debts")
    suspend fun clearAllDebts()
}
