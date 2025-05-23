/*-
 * ========================LICENSE_START=================================
 * flyway-database-postgresql
 * ========================================================================
 * Copyright (C) 2010 - 2025 Red Gate Software Ltd
 * ========================================================================
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * =========================LICENSE_END==================================
 */
package org.flywaydb.database.postgresql;

import lombok.CustomLog;
import org.flywaydb.core.api.configuration.Configuration;
import org.flywaydb.core.internal.parser.*;
import org.flywaydb.core.internal.sqlscript.Delimiter;
import org.flywaydb.core.internal.sqlscript.ParsedSqlStatement;

import java.io.IOException;
import java.util.List;
import java.util.regex.Pattern;

@CustomLog
public class PostgreSQLParser extends Parser {
    private static final Pattern COPY_FROM_STDIN_REGEX = Pattern.compile("^COPY( .*)? FROM STDIN");
    private static final Pattern CREATE_DATABASE_TABLESPACE_SUBSCRIPTION_REGEX = Pattern.compile("^(CREATE|DROP) (DATABASE|TABLESPACE|SUBSCRIPTION)");
    private static final Pattern ALTER_SYSTEM_REGEX = Pattern.compile("^ALTER SYSTEM");
    private static final Pattern CREATE_INDEX_CONCURRENTLY_REGEX = Pattern.compile("^(CREATE|DROP)( UNIQUE)? INDEX CONCURRENTLY");
    private static final Pattern REINDEX_REGEX = Pattern.compile("^REINDEX( VERBOSE)? (SCHEMA|DATABASE|SYSTEM)");
    private static final Pattern VACUUM_REGEX = Pattern.compile("^VACUUM");
    private static final Pattern DISCARD_ALL_REGEX = Pattern.compile("^DISCARD ALL");
    private static final Pattern ALTER_TYPE_ADD_VALUE_REGEX = Pattern.compile("^ALTER TYPE( .*)? ADD VALUE");

    private static final StatementType COPY = new StatementType();

    public PostgreSQLParser(Configuration configuration, ParsingContext parsingContext) {
        super(configuration, parsingContext, 3);
    }

    @Override
    protected char getAlternativeStringLiteralQuote() {
        return '$';
    }

    @Override
    protected ParsedSqlStatement createStatement(PeekingReader reader, Recorder recorder,
        int statementPos, int statementLine, int statementCol, int nonCommentPartPos, int nonCommentPartLine,
        int nonCommentPartCol, StatementType statementType, boolean canExecuteInTransaction, Delimiter delimiter,
        String sql, List<Token> tokens, boolean batchable) throws IOException {

        if (statementType == COPY) {
            return new PostgreSQLCopyParsedStatement(nonCommentPartPos, nonCommentPartLine, nonCommentPartCol,
                                                     sql.substring(nonCommentPartPos - statementPos),
                                                     readCopyData(reader, recorder));
        }
        return super.createStatement(reader, recorder, statementPos, statementLine, statementCol, nonCommentPartPos,
            nonCommentPartLine, nonCommentPartCol, statementType, canExecuteInTransaction, delimiter, sql, tokens,
            batchable);
    }

    @Override
    protected void adjustBlockDepth(ParserContext context, List<Token> tokens, Token keyword, PeekingReader reader) {
        String keywordText = keyword.getText();

        if (lastTokenIs(tokens, context.getParensDepth(), "BEGIN") && "ATOMIC".equalsIgnoreCase(keywordText)) {
            context.increaseBlockDepth("ATOMIC");
        }
        if ("CASE".equalsIgnoreCase(keywordText) && "ATOMIC".equals(context.getBlockInitiator())) {
            context.increaseBlockDepth("CASE");
        }

        if (context.getBlockDepth() > 0 &&  "END".equalsIgnoreCase(keywordText) &&
            ("ATOMIC".equals(context.getBlockInitiator()) || "CASE".equals(context.getBlockInitiator()))) {
            context.decreaseBlockDepth();
        }
    }

    private String readCopyData(PeekingReader reader, Recorder recorder) throws IOException {
        // Skip end of current line after ;
        reader.readUntilIncluding('\n');

        recorder.start();
        boolean done = false;
        do {
            String line = reader.readUntilIncluding('\n');
            if ("\\.".equals(line.trim())) {
                done = true;
            } else {
                recorder.confirm();
            }
        } while (!done);

        return recorder.stop();
    }

    @Override
    protected StatementType detectStatementType(String simplifiedStatement, ParserContext context, PeekingReader reader) {
        if (COPY_FROM_STDIN_REGEX.matcher(simplifiedStatement).matches()) {
            return COPY;
        }

        return super.detectStatementType(simplifiedStatement, context, reader);
    }

    @Override
    protected Boolean detectCanExecuteInTransaction(String simplifiedStatement, List<Token> keywords) {
        if (CREATE_DATABASE_TABLESPACE_SUBSCRIPTION_REGEX.matcher(simplifiedStatement).matches()
                || ALTER_SYSTEM_REGEX.matcher(simplifiedStatement).matches()
                || CREATE_INDEX_CONCURRENTLY_REGEX.matcher(simplifiedStatement).matches()
                || REINDEX_REGEX.matcher(simplifiedStatement).matches()
                || VACUUM_REGEX.matcher(simplifiedStatement).matches()
                || DISCARD_ALL_REGEX.matcher(simplifiedStatement).matches()) {
            return false;
        }

        boolean isDBVerUnder12 = true;
        try {
            isDBVerUnder12 = !parsingContext.getDatabase().getVersion().isAtLeast("12");
        } catch (Exception e) {
            LOG.debug("Unable to determine database version: " + e.getMessage());
        }

        if (isDBVerUnder12 && ALTER_TYPE_ADD_VALUE_REGEX.matcher(simplifiedStatement).matches()) {
            return false;
        }

        return null;
    }

    @SuppressWarnings("Duplicates")
    @Override
    protected Token handleAlternativeStringLiteral(PeekingReader reader, ParserContext context, int pos, int line, int col) throws IOException {
        // dollarQuote is required because in Postgres, literals encased in $$ can be given a label, as in:
        // $label$This is a string literal$label$
        String dollarQuote = (char) reader.read() + reader.readUntilIncluding('$');
        reader.swallowUntilExcluding(dollarQuote);
        reader.swallow(dollarQuote.length());
        return new Token(TokenType.STRING, pos, line, col, null, null, context.getParensDepth());
    }
}
