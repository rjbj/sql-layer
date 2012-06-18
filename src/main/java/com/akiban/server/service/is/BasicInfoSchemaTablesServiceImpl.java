/**
 * END USER LICENSE AGREEMENT (“EULA”)
 *
 * READ THIS AGREEMENT CAREFULLY (date: 9/13/2011):
 * http://www.akiban.com/licensing/20110913
 *
 * BY INSTALLING OR USING ALL OR ANY PORTION OF THE SOFTWARE, YOU ARE ACCEPTING
 * ALL OF THE TERMS AND CONDITIONS OF THIS AGREEMENT. YOU AGREE THAT THIS
 * AGREEMENT IS ENFORCEABLE LIKE ANY WRITTEN AGREEMENT SIGNED BY YOU.
 *
 * IF YOU HAVE PAID A LICENSE FEE FOR USE OF THE SOFTWARE AND DO NOT AGREE TO
 * THESE TERMS, YOU MAY RETURN THE SOFTWARE FOR A FULL REFUND PROVIDED YOU (A) DO
 * NOT USE THE SOFTWARE AND (B) RETURN THE SOFTWARE WITHIN THIRTY (30) DAYS OF
 * YOUR INITIAL PURCHASE.
 *
 * IF YOU WISH TO USE THE SOFTWARE AS AN EMPLOYEE, CONTRACTOR, OR AGENT OF A
 * CORPORATION, PARTNERSHIP OR SIMILAR ENTITY, THEN YOU MUST BE AUTHORIZED TO SIGN
 * FOR AND BIND THE ENTITY IN ORDER TO ACCEPT THE TERMS OF THIS AGREEMENT. THE
 * LICENSES GRANTED UNDER THIS AGREEMENT ARE EXPRESSLY CONDITIONED UPON ACCEPTANCE
 * BY SUCH AUTHORIZED PERSONNEL.
 *
 * IF YOU HAVE ENTERED INTO A SEPARATE WRITTEN LICENSE AGREEMENT WITH AKIBAN FOR
 * USE OF THE SOFTWARE, THE TERMS AND CONDITIONS OF SUCH OTHER AGREEMENT SHALL
 * PREVAIL OVER ANY CONFLICTING TERMS OR CONDITIONS IN THIS AGREEMENT.
 */

package com.akiban.server.service.is;

import com.akiban.ais.model.AkibanInformationSchema;
import com.akiban.ais.model.Column;
import com.akiban.ais.model.GroupIndex;
import com.akiban.ais.model.Index;
import com.akiban.ais.model.IndexColumn;
import com.akiban.ais.model.Schema;
import com.akiban.ais.model.TableIndex;
import com.akiban.ais.model.TableName;
import com.akiban.ais.model.UserTable;
import com.akiban.ais.model.aisb2.AISBBasedBuilder;
import com.akiban.ais.model.aisb2.NewAISBuilder;
import com.akiban.qp.expression.IndexKeyRange;
import com.akiban.qp.memoryadapter.MemoryAdapter;
import com.akiban.qp.memoryadapter.MemoryGroupCursor;
import com.akiban.qp.memoryadapter.MemoryTableFactory;
import com.akiban.qp.operator.API;
import com.akiban.qp.operator.Cursor;
import com.akiban.qp.operator.IndexScanSelector;
import com.akiban.qp.row.Row;
import com.akiban.qp.row.ValuesRow;
import com.akiban.qp.rowtype.RowType;
import com.akiban.server.service.Service;
import com.akiban.server.service.session.Session;
import com.akiban.server.service.session.SessionService;
import com.akiban.server.store.AisHolder;
import com.akiban.server.store.SchemaManager;
import com.akiban.server.store.statistics.IndexStatistics;
import com.akiban.server.types.AkType;
import com.google.inject.Inject;

import java.util.Iterator;

import static com.akiban.qp.memoryadapter.MemoryGroupCursor.GroupScan;

public class BasicInfoSchemaTablesServiceImpl implements Service<BasicInfoSchemaTablesService>, BasicInfoSchemaTablesService {
    static final TableName SCHEMATA = new TableName(TableName.AKIBAN_INFORMATION_SCHEMA, "schemata");
    static final TableName TABLES = new TableName(TableName.AKIBAN_INFORMATION_SCHEMA, "tables");
    static final TableName COLUMNS = new TableName(TableName.AKIBAN_INFORMATION_SCHEMA, "columns");
    static final TableName INDEXES = new TableName(TableName.AKIBAN_INFORMATION_SCHEMA, "indexes");
    static final TableName INDEX_COLUMNS = new TableName(TableName.AKIBAN_INFORMATION_SCHEMA, "index_columns");

    private final AisHolder aisHolder;
    private final SchemaManager schemaManager;
    private final SessionService sessionService;

    @Inject
    public BasicInfoSchemaTablesServiceImpl(AisHolder aisHolder, SchemaManager schemaManager, SessionService sessionService) {
        this.aisHolder = aisHolder;
        this.schemaManager = schemaManager;
        this.sessionService = sessionService;
    }

    @Override
    public BasicInfoSchemaTablesServiceImpl cast() {
        return this;
    }

    @Override
    public Class<BasicInfoSchemaTablesService> castClass() {
        return BasicInfoSchemaTablesService.class;
    }

    @Override
    public void start() {
        AkibanInformationSchema ais = createTablesToRegister();

        Session session = sessionService.createSession();

        // SCHEMAS
        UserTable schemata = ais.getUserTable(SCHEMATA);
        schemaManager.registerMemoryInformationSchemaTable(session, schemata, new SchemaFactory(schemata));
        // TABLES
        UserTable tables = ais.getUserTable(TABLES);
        schemaManager.registerMemoryInformationSchemaTable(session, tables, new TableFactory(tables));
        // COLUMNS
        UserTable columns = ais.getUserTable(COLUMNS);
        schemaManager.registerMemoryInformationSchemaTable(session, columns, new ColumnsFactory(columns));
        // INDEXES
        UserTable indexes = ais.getUserTable(INDEXES);
        schemaManager.registerMemoryInformationSchemaTable(session, indexes, new IndexesFactory(indexes));
        // INDEX_COLUMNS
        UserTable index_columns = ais.getUserTable(INDEX_COLUMNS);
        schemaManager.registerMemoryInformationSchemaTable(session, index_columns, new IndexColumnsFactory(index_columns));

        // update attachFactories() when adding new tables

        session.close();
    }

    @Override
    public void stop() {
        // Nothing
    }

    @Override
    public void crash() {
        // Nothing
    }

    private abstract class BasicFactoryBase implements MemoryTableFactory {
        private final UserTable sourceTable;

        public BasicFactoryBase(UserTable sourceTable) {
            this.sourceTable = sourceTable;
        }

        @Override
        public TableName getName() {
            return sourceTable.getName();
        }

        @Override
        public UserTable getTableDefinition() {
            return sourceTable;
        }

        @Override
        public Cursor getIndexCursor(Index index, Session session, IndexKeyRange keyRange, API.Ordering ordering, IndexScanSelector scanSelector) {
            throw new UnsupportedOperationException();
        }

        @Override
        public IndexStatistics computeIndexStatistics(Session session, Index index) {
            throw new UnsupportedOperationException();
        }

        protected RowType getRowType(MemoryAdapter adapter) {
            return adapter.schema().userTableRowType(adapter.schema().ais().getUserTable(sourceTable.getName()));
        }
    }

    private class SchemaFactory extends BasicFactoryBase {
        public SchemaFactory(UserTable sourceTable) {
            super(sourceTable);
        }

        @Override
        public GroupScan getGroupScan(MemoryAdapter adapter) {
            return new Scan(getRowType(adapter));
        }

        @Override
        public long rowCount() {
            return aisHolder.getAis().getSchemas().size();
        }

        private class Scan implements GroupScan {
            final RowType rowType;
            final Iterator<Schema> it = aisHolder.getAis().getSchemas().values().iterator();
            int rowCounter;

            public Scan(RowType rowType) {
                this.rowType = rowType;
            }

            @Override
            public Row next() {
                if(!it.hasNext()) {
                    return null;
                }
                Schema schema = it.next();
                return new ValuesRow(rowType,
                                     schema.getName(),
                                     null, // owner
                                     null, // charset
                                     null, // collation
                                     ++rowCounter /*hidden pk*/);

            }

            @Override
            public void close() {
            }
        }
    }

    private class TableFactory extends BasicFactoryBase {
        public TableFactory(UserTable sourceTable) {
            super(sourceTable);
        }

        @Override
        public GroupScan getGroupScan(MemoryAdapter adapter) {
            return new Scan(getRowType(adapter));
        }

        @Override
        public long rowCount() {
            return aisHolder.getAis().getUserTables().size();
        }

        private class Scan implements GroupScan {
            final RowType rowType;
            final Iterator<UserTable> it = aisHolder.getAis().getUserTables().values().iterator();
            int rowCounter;

            public Scan(RowType rowType) {
                this.rowType = rowType;
            }

            @Override
            public Row next() {
                if(!it.hasNext()) {
                    return null;
                }
                UserTable table = it.next();
                final String tableType = table.hasMemoryTableFactory() ? "DICTIONARY VIEW" : "TABLE";
                return new ValuesRow(rowType,
                                     table.getName().getSchemaName(),
                                     table.getName().getTableName(),
                                     tableType,
                                     null, // charset schema
                                     table.getCharsetAndCollation().charset(),
                                     null, // collation schema
                                     table.getCharsetAndCollation().collation(),
                                     ++rowCounter /* hidden pk */);
            }

            @Override
            public void close() {
            }
        }
    }

    private class ColumnsFactory extends BasicFactoryBase {
        public ColumnsFactory(UserTable sourceTable) {
            super(sourceTable);
        }

        @Override
        public GroupScan getGroupScan(MemoryAdapter adapter) {
            return new Scan(getRowType(adapter));
        }

        @Override
        public long rowCount() {
            long count = 0;
            for(UserTable table : aisHolder.getAis().getUserTables().values()) {
                count += table.getColumns().size();
            }
            return count;
        }

        private class Scan implements GroupScan {
            final RowType rowType;
            final Iterator<UserTable> tableIt = aisHolder.getAis().getUserTables().values().iterator();
            Iterator<Column> columnIt;
            int rowCounter;

            public Scan(RowType rowType) {
                this.rowType = rowType;
            }

            @Override
            public Row next() {
                if(columnIt == null || !columnIt.hasNext()) {
                    if(tableIt.hasNext()) {
                        columnIt = tableIt.next().getColumns().iterator();
                    } else {
                        return null;
                    }
                }
                Column column = columnIt.next();

                Integer scale = null;
                Integer precision = null;
                if(column.getType().akType() == AkType.DECIMAL) {
                    scale = column.getTypeParameter1().intValue();
                    precision = column.getTypeParameter2().intValue();
                }
                final Long length;
                if(column.getType().fixedSize()) {
                    length = column.getMaxStorageSize();
                } else {
                    length = column.getTypeParameter1();
                }

                return new ValuesRow(rowType,
                                     column.getTable().getName().getSchemaName(),
                                     column.getTable().getName().getTableName(),
                                     column.getName(),
                                     column.getPosition(),
                                     column.getType().name(),
                                     column.getNullable() ? "YES" : "NO",
                                     length,
                                     scale,
                                     precision,
                                     column.getPrefixSize(),
                                     column.getInitialAutoIncrementValue(),
                                     null, // charset schema
                                     column.getCharsetAndCollation().charset(),
                                     null, // collation schema
                                     column.getCharsetAndCollation().collation(),
                                     ++rowCounter /* hidden pk */);
            }

            @Override
            public void close() {
            }
        }
    }

    private class IndexesFactory extends BasicFactoryBase {
        public IndexesFactory(UserTable sourceTable) {
            super(sourceTable);
        }

        @Override
        public GroupScan getGroupScan(MemoryAdapter adapter) {
            return new Scan(getRowType(adapter));
        }

        @Override
        public long rowCount() {
            long count = 0;
            for(UserTable table : aisHolder.getAis().getUserTables().values()) {
                count += table.getIndexes().size();
            }
            return count;
        }

        private class Scan implements GroupScan {
            final RowType rowType;
            final Iterator<UserTable> tableIt = aisHolder.getAis().getUserTables().values().iterator();
            Iterator<TableIndex> tableIndexIt;
            Iterator<GroupIndex> groupIndexIt;
            UserTable curTable;
            int rowCounter;

            public Scan(RowType rowType) {
                this.rowType = rowType;
            }

            private Index advance() {
                while(tableIndexIt == null || !tableIndexIt.hasNext()) {
                    while(groupIndexIt != null && groupIndexIt.hasNext()) {
                        GroupIndex index = groupIndexIt.next();
                        if(index.leafMostTable() == curTable) {
                            return index;
                        }
                    }
                    if(tableIt.hasNext()) {
                        curTable = tableIt.next();
                        tableIndexIt = curTable.getIndexes().iterator();
                        groupIndexIt = curTable.getGroup().getIndexes().iterator();
                    } else {
                        return null;
                    }
                }
                return tableIndexIt.next();
            }

            @Override
            public Row next() {
                Index index = advance();
                if(index == null) {
                    return null;
                }
                final String indexType;
                if(index.isPrimaryKey()) {
                    indexType = Index.PRIMARY_KEY_CONSTRAINT;
                } else if(index.isUnique()) {
                    indexType = Index.UNIQUE_KEY_CONSTRAINT;
                } else {
                    indexType = "INDEX";
                }
                return new ValuesRow(rowType,
                                     curTable.getName().getSchemaName(),
                                     curTable.getName().getTableName(),
                                     null, // constraint_name
                                     index.getIndexName().getName(),
                                     indexType,
                                     index.isUnique() ? "YES" : "NO",
                                     index.isGroupIndex() ? index.getJoinType().name() : null,
                                     ++rowCounter /*hidden pk*/);
            }

            @Override
            public void close() {
            }
        }
    }

    private class IndexColumnsFactory extends BasicFactoryBase {
        public IndexColumnsFactory(UserTable sourceTable) {
            super(sourceTable);
        }

        @Override
        public MemoryGroupCursor.GroupScan getGroupScan(MemoryAdapter adapter) {
            return new Scan(getRowType(adapter));
        }

        @Override
        public long rowCount() {
            long count = 0;
            for(UserTable table : aisHolder.getAis().getUserTables().values()) {
                for(Index index : table.getIndexes()) {
                    count += index.getKeyColumns().size();
                }
            }
            return count;
        }

        private class Scan implements GroupScan {
            final RowType rowType;
            final Iterator<UserTable> tableIt = aisHolder.getAis().getUserTables().values().iterator();
            Iterator<TableIndex> indexIt;
            Iterator<IndexColumn> indexColumnIt;
            int rowCounter;

            public Scan(RowType rowType) {
                this.rowType = rowType;
            }

            private boolean advanceIfNeeded() {
                while(indexColumnIt == null || !indexColumnIt.hasNext()) {
                    while(indexIt == null || !indexIt.hasNext()) {
                        if(tableIt.hasNext()) {
                            indexIt = tableIt.next().getIndexes().iterator();
                        } else {
                            return false;
                        }
                    }
                    if(indexIt.hasNext()) {
                        indexColumnIt = indexIt.next().getKeyColumns().iterator();
                        break;
                    }
                }
                return true;
            }

            @Override
            public Row next() {
                if(!advanceIfNeeded()) {
                    return null;
                }
                IndexColumn indexColumn = indexColumnIt.next();
                return new ValuesRow(rowType,
                                     indexColumn.getIndex().getIndexName().getSchemaName(),
                                     indexColumn.getColumn().getTable().getGroup().getName(),
                                     indexColumn.getIndex().getIndexName().getName(),
                                     indexColumn.getColumn().getTable().getName().getTableName(),
                                     indexColumn.getColumn().getName(),
                                     indexColumn.getPosition(),
                                     indexColumn.isAscending() ? "YES" : "NO",
                                     indexColumn.getIndexedLength(),
                                     ++rowCounter /*hidden pk*/);
            }

            @Override
            public void close() {
            }
        }
    }

    //
    // Package, for testing
    //

    static AkibanInformationSchema createTablesToRegister() {
        NewAISBuilder builder = AISBBasedBuilder.create();
        builder.userTable(SCHEMATA)
                .colString("schema_name", 128, false)
                .colString("schema_owner", 128, true)
                .colString("default_character_set_name", 128, true)
                .colString("default_collation_name", 128, true);
        //.pk("schema_name")
        builder.userTable(TABLES)
                .colString("table_schema", 128, false)
                .colString("table_name", 128, false)
                .colString("table_type", 128, false)
                .colString("character_set_schema", 128, true)
                .colString("character_set_name", 128, true)
                .colString("collation_schema", 128, true)
                .colString("collation_name", 128, true);
        //primary key (schema_name, table_name),
        //foreign_key (schema_name) references SCHEMATA (schema_name),
        //foreign key (character_set_schema, character_set_name) references CHARACTER_SETS,
        //foreign key (collations_schema, collation_name) references COLLATIONS;
        builder.userTable(COLUMNS)
                .colString("schema_name", 128, false)
                .colString("table_name", 128, false)
                .colString("column_name", 128, false)
                .colBigInt("position", false)
                .colString("type", 32, false)
                .colString("nullable", 3, false)
                .colBigInt("length", false)
                .colBigInt("precision", true)
                .colBigInt("scale", true)
                .colBigInt("prefix_size", true)
                .colBigInt("identity_start", true)
                .colString("character_set_schema", 128, true)
                .colString("character_set_name", 128, true)
                .colString("collation_schema", 128, true)
                .colString("collation_name", 128, true);
        //primary key(schema_name, table_name, column_name),
        //foreign key(schema_name, table_name) references TABLES (schema_name, table_name),
        //foreign key (type) references TYPES (type_name),
        //foreign key (character_set_schema, character_set_name) references CHARACTER_SETS,
        //foreign key (collation_schema, collation_name) references COLLATIONS
        builder.userTable(INDEXES)
                .colString("schema_name", 128, false)
                .colString("table_name", 128, false)
                .colString("constraint_name", 128, true)
                .colString("index_name", 128, false)
                .colString("index_type", 128, false)
                .colString("is_unique", 3, false)
                .colString("join_type", 32, true);
        //primary key(schema_name, table_name, index_name),
        //foreign key (schema_name, table_name, constraint_name)
        //references TABLE_CONSTRAINTS (schema_name, table_name, constraint_name),
        //foreign key (schema_name, table_name) references TABLES (schema_name, table_name));
        builder.userTable(INDEX_COLUMNS)
                .colString("schema_name", 128, false)
                .colString("group_name", 128, false)
                .colString("index_name", 128, false)
                .colString("table_name", 128, false)
                .colString("column_name", 128, false)
                .colBigInt("ordinal_position", false)
                .colString("is_ascending", 128, false)
                .colBigInt("indexed_length", true);
        //primary key(schema_name, group_name, index_name, table_name, column_name),
        //foreign key(schema_name, group_name, index_name)
        //references indexes (schema_name, group_name, index_name),
        //foreign key (schema_name, table_name, column_name)
        //references columns (schema_name, table_name, column_name));

        return builder.ais(false);
    }

    void attachFactories(AkibanInformationSchema ais) {
        // SCHEMAS
        UserTable schemata = ais.getUserTable(SCHEMATA);
        schemata.setMemoryTableFactory(new SchemaFactory(schemata));
        // TABLES
        UserTable tables = ais.getUserTable(TABLES);
        tables.setMemoryTableFactory(new TableFactory(tables));
        // COLUMNS
        UserTable columns = ais.getUserTable(COLUMNS);
        columns.setMemoryTableFactory(new ColumnsFactory(columns));
        // INDEXES
        UserTable indexes = ais.getUserTable(INDEXES);
        indexes.setMemoryTableFactory(new IndexesFactory(indexes));
        // INDEX_COLUMNS
        UserTable index_columns = ais.getUserTable(INDEX_COLUMNS);
        index_columns.setMemoryTableFactory(new IndexColumnsFactory(index_columns));
    }
}
