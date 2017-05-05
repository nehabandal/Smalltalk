package smalltalk.compiler;

import org.antlr.symtab.Scope;
import org.antlr.symtab.Symbol;
import org.antlr.symtab.VariableSymbol;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.TerminalNode;
import smalltalk.compiler.symbols.*;

import java.util.List;

import static smalltalk.compiler.misc.Utils.*;

/**
 * Fill STBlock, STMethod objects in Symbol table with bytecode,
 * {@link STCompiledBlock}.
 */
public class CodeGenerator extends SmalltalkBaseVisitor<Code> {
    public static final boolean dumpCode = false;
    public STClass currentClassScope;
    public Scope currentScope;

    /**
     * With which compiler are we generating code?
     */
    public final Compiler compiler;
    int count = 0;

    public CodeGenerator(Compiler compiler) {
        this.compiler = compiler;
    }

    /**
     * This and defaultResult() critical to getting code to bubble up the
     * visitor call stack when we don't implement every method.
     */
    @Override
    protected Code aggregateResult(Code aggregate, Code nextResult) {
        if (aggregate != Code.None) {
            if (nextResult != Code.None) {
                return aggregate.join(nextResult);
            }
            return aggregate;
        } else {
            return nextResult;
        }
    }

    @Override
    protected Code defaultResult() {
        return Code.None;
    }

    @Override
    public Code visitMain(SmalltalkParser.MainContext ctx) {
        currentClassScope = ctx.classScope;
        Code code = Code.None;
        if (currentClassScope != null) {
            pushScope(ctx.scope);
            code = visitChildren(ctx);
            code = aggregateResult(code,Compiler.push_atEnd());
            ctx.scope.compiledBlock = new STCompiledBlock(currentClassScope, (STBlock) currentScope);
            ctx.scope.compiledBlock.bytecode = code.bytes();
            currentClassScope = null;
        }

        return code;
    }

    @Override
    public Code visitFile(SmalltalkParser.FileContext ctx) {
        currentScope = compiler.symtab.GLOBALS;
        Code code = visitChildren(ctx);
        return code;
    }

    @Override
    public Code visitClassDef(SmalltalkParser.ClassDefContext ctx) {
        currentClassScope = ctx.scope;
        pushScope(ctx.scope);
        Code code = Code.None;
        code = code.join(visitChildren(ctx));
        popScope();
        currentClassScope = null;
        return code;
    }

    public STCompiledBlock getCompiledPrimitive(STPrimitiveMethod primitive) {
        STCompiledBlock compiledMethod = new STCompiledBlock(currentClassScope, primitive);
        return compiledMethod;
    }

    @Override
    public Code visitNamedMethod(SmalltalkParser.NamedMethodContext ctx) {
        pushScope(ctx.scope);
        Code code = visitChildren(ctx);
        ctx.scope.compiledBlock = new STCompiledBlock(currentClassScope, (STBlock) currentScope);
        ctx.scope.compiledBlock.bytecode = code.bytes();
        popScope();
        return code;
    }

    @Override
    public Code visitSmalltalkMethodBlock(SmalltalkParser.SmalltalkMethodBlockContext ctx) {
        Code code = visitChildren(ctx);
        return code;
    }

    @Override
    public Code visitPrimitiveMethodBlock(SmalltalkParser.PrimitiveMethodBlockContext ctx) {
        return Code.None;
    }

    /**
     * All expressions have values. Must pop each expression value off, except
     * last one, which is the block return value. Visit method for blocks will
     * issue block_return instruction. Visit method for method will issue
     * pop self return.  If last expression is ^expr, the block_return or
     * pop self return is dead code but it is always there as a failsafe.
     * <p>
     * localVars? expr ('.' expr)* '.'?
     */
    @Override
    public Code visitFullBody(SmalltalkParser.FullBodyContext ctx) {
        Code code = Code.None;
        if (ctx.localVars() != null) {
            code = code.join(visit(ctx.localVars()));
        }
        int i = 0;
        for (SmalltalkParser.StatContext statContext : ctx.stat()) {
            i++;
            code = code.join(visit(statContext));
            if (i != ctx.stat().size())
                code = aggregateResult(code,Compiler.push_pop());

        }
        if (currentScope instanceof STMethod)
            if (!currentScope.getName().equals("main")) {
                code = aggregateResult(code,Compiler.push_atEnd());
            }
        return code;
    }

    @Override
    public Code visitEmptyBody(SmalltalkParser.EmptyBodyContext ctx) {
        Code code = Code.None;
        if (currentClassScope.getName().equals("MainClass")) {
            code = Code.of(Bytecode.NIL);
        } else {
            code = code.join(Code.of(
                    Bytecode.SELF,
                    Bytecode.RETURN
            ));
        }
        return code;
    }

    @Override
    public Code visitBlock(SmalltalkParser.BlockContext ctx) {
        currentScope = ctx.scope;
        pushScope(currentScope);
        STBlock block = (STBlock) currentScope;
        Code blkcode = Code.of(Bytecode.BLOCK).join(shortToBytes(block.index));
        Code code = visitChildren(ctx);
        code = aggregateResult(code, Code.of(Bytecode.BLOCK_RETURN));
        ctx.scope.compiledBlock = new STCompiledBlock(currentClassScope, block);
        ctx.scope.compiledBlock.bytecode = code.bytes();
        popScope();

        return blkcode;
    }

    @Override
    public Code visitAssign(SmalltalkParser.AssignContext ctx) {
        Code code = Code.None;
        code = code.join(aggregateResult(
                visit(ctx.messageExpression()),
                visit(ctx.lvalue())));
        return code;

    }

    @Override
    public Code visitLvalue(SmalltalkParser.LvalueContext ctx) {
        Symbol sym = ctx.sym;
        if (sym instanceof STField) {
            Code code = Code.of(Bytecode.STORE_FIELD).join(shortToBytes(sym.getInsertionOrderNumber()));
            return code;
        } else {
            Code code = Code.of(Bytecode.STORE_LOCAL).join(shortToBytes(0)).join(shortToBytes(sym.getInsertionOrderNumber()));
            return code;
        }
    }

    @Override
    public Code visitMessageExpression(SmalltalkParser.MessageExpressionContext ctx) {
        return visit(ctx.keywordExpression());
    }

    @Override
    public Code visitPassThrough(SmalltalkParser.PassThroughContext ctx) {
        return visit(ctx.recv);
    }

    @Override
    public Code visitBop(SmalltalkParser.BopContext ctx) {
        Code code = Code.of(Bytecode.SEND).join(shortToBytes(1).join(toLiteral(currentClassScope.stringTable.add(ctx.getText()))));
        return code;
    }

    @Override
    public Code visitBinaryExpression(SmalltalkParser.BinaryExpressionContext ctx) {
        Code code = Code.None;
        int i = 0, j = 0, k = 0;
        List<SmalltalkParser.UnaryExpressionContext> unaryExpressionContexts = ctx.unaryExpression();
        List<SmalltalkParser.BopContext> bopContexts = ctx.bop();
        while (k < unaryExpressionContexts.size() + bopContexts.size()) {
            code = code.join(visit(unaryExpressionContexts.get(i)));
            i++;
            k++;
            if (i > 1) {
                code = code.join(visit(ctx.bop(j)));
                j++;
            }
            k++;
        }
        return code;
    }

    @Override
    public Code visitOperatorMethod(SmalltalkParser.OperatorMethodContext ctx) {
        pushScope(ctx.scope);
        ctx.scope.compiledBlock = new STCompiledBlock(currentClassScope, (STBlock) currentScope);
        popScope();
        return Code.None;
    }

    @Override
    public Code visitKeywordMethod(SmalltalkParser.KeywordMethodContext ctx) {
        pushScope(ctx.scope);
        Code code = visit(ctx.methodBlock());
        ctx.scope.compiledBlock = new STCompiledBlock(currentClassScope, (STBlock) currentScope);
        ctx.scope.compiledBlock.bytecode = code.bytes();
        popScope();
        return code;
    }

    @Override
    public Code visitId(SmalltalkParser.IdContext ctx) {
        String id = ctx.ID().getText();
        Code code = Code.None;
        Symbol sym = ctx.sym;
        int i = 0;
        if (sym instanceof STField) {
            code = code.join(Code.of(Bytecode.PUSH_FIELD).join(shortToBytes(((STBlock) currentScope).getLocalIndex(ctx.ID().getText()))));
            return code;
        } else if (sym instanceof VariableSymbol) {
            return code.join(Code.of(Bytecode.PUSH_LOCAL).join(shortToBytes(0)).join(shortToBytes(sym.getInsertionOrderNumber())));
        } else {
            int index = currentClassScope.stringTable.add(id);
            return code.join(Code.of(Bytecode.PUSH_GLOBAL).join(toLiteral(index)));
        }
    }

    @Override
    public Code visitLiteral(SmalltalkParser.LiteralContext ctx) {
        String id = ctx.getText();
        if (id.contains("\'")) {
            id = id.replace("\'", "");
        }
        if (id.equals("true")) {
            return Code.of(Bytecode.TRUE);
        } else if (id.equals("false")) {
            return Code.of(Bytecode.FALSE);
        } else if (id.equals("nil")) {
            return Code.of(Bytecode.NIL);
        } else if (id.equals("self")) {
            return Code.of(Bytecode.SELF);
        }
        if (ctx.NUMBER() == null) {
            int index = currentClassScope.stringTable.add(id);
            return Code.of(Bytecode.PUSH_LITERAL).join(toLiteral(index));
        } else {
            int num = Integer.parseInt(ctx.getText());
            return Code.of(Bytecode.PUSH_INT).join(intToBytes(num));
        }
    }

    @Override
    public Code visitKeywordSend(SmalltalkParser.KeywordSendContext ctx) {
        Code code = Code.None;
        code = code.join(visit(ctx.recv));
        for (SmalltalkParser.BinaryExpressionContext binaryExpressionContext : ctx.args) {
            code = code.join(visit(binaryExpressionContext));
        }
        code = sendKeywordMsg(ctx.recv, code, ctx.args, ctx.KEYWORD());
        return code;
    }

    @Override
    public Code visitUnarySuperMsgSend(SmalltalkParser.UnarySuperMsgSendContext ctx) {
        Code code = Code.of(Bytecode.SELF);
        code = code.join(Code.of(Bytecode.SEND_SUPER).join(shortToBytes(0).join(toLiteral(currentClassScope.stringTable.add(ctx.ID().getText())))));
        return code;
    }

    @Override
    public Code visitUnaryMsgSend(SmalltalkParser.UnaryMsgSendContext ctx) {
        Code code = visitChildren(ctx);
        code = code.join(Code.of(Bytecode.SEND).join(shortToBytes(0).join(toLiteral(currentClassScope.stringTable.add(ctx.ID().getText())))));
        return code;
    }

    @Override
    public Code visitReturn(SmalltalkParser.ReturnContext ctx) {
        Code e = visit(ctx.messageExpression());
        if (compiler.genDbg) {
            e = Code.join(e, dbg(ctx.start)); // put dbg after expression as that is when it executes
        }
        Code code = e.join(Compiler.method_return());
        return code;
    }

    public void pushScope(Scope scope) {
        currentScope = scope;
    }

    public void popScope() {
//		if ( currentScope.getEnclosingScope()!=null ) {
//			System.out.println("popping from " + currentScope.getScopeName() + " to " + currentScope.getEnclosingScope().getScopeName());
//		}
//		else {
//			System.out.println("popping from " + currentScope.getScopeName() + " to null");
//		}
        currentScope = currentScope.getEnclosingScope();
    }

    public int getLiteralIndex(String s) {
        return 0;
    }

    public Code dbgAtEndMain(Token t) {
        int charPos = t.getCharPositionInLine() + t.getText().length();
        return dbg(t.getLine(), charPos);
    }

    public Code dbgAtEndBlock(Token t) {
        int charPos = t.getCharPositionInLine() + t.getText().length();
        charPos -= 1; // point at ']'
        return dbg(t.getLine(), charPos);
    }

    public Code dbg(Token t) {
        return dbg(t.getLine(), t.getCharPositionInLine());
    }

    public Code dbg(int line, int charPos) {
        return Compiler.dbg(getLiteralIndex(compiler.getFileName()), line, charPos);
    }

    public Code store(String id) {
        return null;
    }

    public Code push(String id) {
        return null;
    }

    public Code sendKeywordMsg(ParserRuleContext receiver,
                               Code receiverCode,
                               List<SmalltalkParser.BinaryExpressionContext> args,
                               List<TerminalNode> keywords) {
        Code code = receiverCode;
        StringBuilder sb = new StringBuilder();
        sb.append(keywords.get(0));
        for (int i = 1; i < keywords.size(); i++) {
            sb.append(keywords.get(i));
        }
        Code e = Code.of(Bytecode.SEND).join(shortToBytes(args.size()).join(toLiteral(currentClassScope.stringTable.add(sb.toString()))));
        Code codenew = aggregateResult(code, e);
        return codenew;
    }

    public String getProgramSourceForSubtree(ParserRuleContext ctx) {
        return null;
    }

}
