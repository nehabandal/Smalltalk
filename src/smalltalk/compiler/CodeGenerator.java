package smalltalk.compiler;

import org.antlr.symtab.Scope;
import org.antlr.symtab.Symbol;
import org.antlr.symtab.VariableSymbol;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.TerminalNode;
import smalltalk.compiler.symbols.*;

import java.util.List;

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

    public CodeGenerator(Compiler compiler) {
        this.compiler = compiler;
    }

    /**
     * This and defaultResult() critical to getting code to bubble up the
     * visitor call stack when we don't implement every method.
     */
    @Override
    protected Code aggregateResult(Code aggregate, Code nextResult) {
        if (aggregate != defaultResult()) {
            if (nextResult != defaultResult()) {
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
        Code code = defaultResult();
        if (currentClassScope != null) {
            pushScope(ctx.scope);
            code = visitChildren(ctx);
            code = aggregateResult(code, Compiler.push_atEnd());
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
        Code code = defaultResult();
        code = code.join(visitChildren(ctx));
        popScope();
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
        Code code = defaultResult();
        if (ctx.localVars() != null) {
            code = code.join(visit(ctx.localVars()));
        }
        List<SmalltalkParser.StatContext> stat = ctx.stat();
        for (int i = 0; i < stat.size(); i++) {
            SmalltalkParser.StatContext statContext = stat.get(i);
            code = code.join(visit(statContext));
            if (i != stat.size() - 1) {
                code = aggregateResult(code, Compiler.push_pop());
            }
        }
        return code;
    }

    @Override
    public Code visitSmalltalkMethodBlock(SmalltalkParser.SmalltalkMethodBlockContext ctx) {
        Code code = visit(ctx.body());
        if(code == defaultResult()) {
            return aggregateResult(code, Compiler.push_self_return());
        }
        return aggregateResult(code, Compiler.push_atEnd());
    }

    @Override
    public Code visitEmptyBody(SmalltalkParser.EmptyBodyContext ctx) {
        Code code = defaultResult();
        if (currentClassScope.getName().equals("MainClass")) {
            code = Compiler.push_nil();
        }
        return code;
    }

    @Override
    public Code visitBlock(SmalltalkParser.BlockContext ctx) {
        currentScope = ctx.scope;
        pushScope(currentScope);
        STBlock block = (STBlock) currentScope;
        Code blkcode = Compiler.push_block(block.index);
        Code code = visitChildren(ctx);
        code = aggregateResult(code, Compiler.push_block_return());
        ctx.scope.compiledBlock = new STCompiledBlock(currentClassScope, block);
        ctx.scope.compiledBlock.bytecode = code.bytes();
        popScope();

        return blkcode;
    }

    @Override
    public Code visitAssign(SmalltalkParser.AssignContext ctx) {
        Code code = defaultResult();
        code = code.join(aggregateResult(
                visit(ctx.messageExpression()),
                visit(ctx.lvalue())));
        return code;

    }

    @Override
    public Code visitLvalue(SmalltalkParser.LvalueContext ctx) {
        Symbol sym = ctx.sym;
        if (sym instanceof STField) {
            return Compiler.push_store_field(sym.getInsertionOrderNumber());
        } else {
            int i = sym.getInsertionOrderNumber();
            int d = ((STBlock) currentScope).getRelativeScopeCount(sym.getScope().getName());
            return Compiler.push_store_local(d, i);
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
        return Compiler.push_send(1, currentClassScope.stringTable.add(ctx.getText()));
    }

    @Override
    public Code visitBinaryExpression(SmalltalkParser.BinaryExpressionContext ctx) {
        Code code = visit(ctx.unaryExpression(0));
        if (ctx.unaryExpression().size() >= 2) {
            for (int i = 1; i < ctx.unaryExpression().size(); i++) {
                code = aggregateResult(code, visit(ctx.unaryExpression(i)));
                code = aggregateResult(code, visit(ctx.bop(i - 1)));
            }
        }
        return code;
    }

    @Override
    public Code visitOperatorMethod(SmalltalkParser.OperatorMethodContext ctx) {
        pushScope(ctx.scope);
        ctx.scope.compiledBlock = new STCompiledBlock(currentClassScope, (STBlock) currentScope);
        popScope();
        return defaultResult();
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
        Code code = defaultResult();
        Symbol sym = ctx.sym;
        if (sym instanceof STField) {
            code = aggregateResult(code, Compiler.push_field(((STBlock) currentScope).getLocalIndex(ctx.ID().getText())));
            return code;
        } else if (sym instanceof VariableSymbol) {
            int i = sym.getInsertionOrderNumber();
            int d = ((STBlock) currentScope).getRelativeScopeCount(sym.getScope().getName());
            return aggregateResult(code, Compiler.push_local(d, i));
        } else {
            int index = currentClassScope.stringTable.add(id);
            return aggregateResult(code, Compiler.push_global(index));
        }
    }

    @Override
    public Code visitLiteral(SmalltalkParser.LiteralContext ctx) {
        String id = ctx.getText();
        if (id.contains("\'")) {
            id = id.replace("\'", "");
        }
        switch (id) {
            case "true":
                return Compiler.push_true();
            case "false":
                return Compiler.push_false();
            case "nil":
                return Compiler.push_nil();
            case "self":
                return Compiler.push_self();
        }
        if (ctx.NUMBER() == null) {
            int index = currentClassScope.stringTable.add(id);
            return Compiler.push_literal(index);
        } else {
            int num = Integer.parseInt(ctx.getText());
            return Compiler.push_int(num);
        }
    }

    @Override
    public Code visitKeywordSend(SmalltalkParser.KeywordSendContext ctx) {
        Code code = defaultResult();
        code = code.join(visit(ctx.recv));
        for (SmalltalkParser.BinaryExpressionContext binaryExpressionContext : ctx.args) {
            code = code.join(visit(binaryExpressionContext));
        }
        code = sendKeywordMsg(ctx.recv, code, ctx.args, ctx.KEYWORD());
        return code;
    }

    @Override
    public Code visitUnarySuperMsgSend(SmalltalkParser.UnarySuperMsgSendContext ctx) {
        Code code = Compiler.push_self();
        code = aggregateResult(code, Compiler.push_send_super(0, currentClassScope.stringTable.add(ctx.ID().getText())));
        return code;
    }

    @Override
    public Code visitUnaryMsgSend(SmalltalkParser.UnaryMsgSendContext ctx) {
        Code code = visitChildren(ctx);
        code = aggregateResult(code, Compiler.push_send(0, currentClassScope.stringTable.add(ctx.ID().getText())));
        return code;
    }

    @Override
    public Code visitReturn(SmalltalkParser.ReturnContext ctx) {
        Code e = visit(ctx.messageExpression());
        if (compiler.genDbg) {
            e = Code.join(e, dbg(ctx.start)); // put dbg after expression as that is when it executes
        }
        return e.join(Compiler.method_return());
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
        StringBuilder sb = new StringBuilder();
        sb.append(keywords.get(0));
        for (int i = 1; i < keywords.size(); i++) {
            sb.append(keywords.get(i));
        }
        Code e = Compiler.push_send(args.size(), currentClassScope.stringTable.add(sb.toString()));
        return aggregateResult(receiverCode, e);
    }

    public String getProgramSourceForSubtree(ParserRuleContext ctx) {
        return null;
    }


}
