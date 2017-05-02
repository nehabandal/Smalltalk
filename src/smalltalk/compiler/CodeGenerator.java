package smalltalk.compiler;

import org.antlr.symtab.Scope;
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
    int index;
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
            code = visit(ctx.body());
            code = code.join(Code.of(
                    Bytecode.SELF,
                    Bytecode.RETURN
            ));
            ((STBlock) currentScope).compiledBlock.bytecode = code.bytes();
            popScope();
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
//        visit(ctx.instanceVars());
        Code code = Code.None;
        for (SmalltalkParser.ClassMethodContext classMethodContext : ctx.classMethod()) {
            code = code.join(visit(classMethodContext));
        }
        for (SmalltalkParser.MethodContext methodContext : ctx.method()) {
            code = code.join(visit(methodContext));
        }

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
        Code code = visit(ctx.methodBlock());
        code = code.join(Code.of(
                Bytecode.SELF,
                Bytecode.RETURN
        ));
        if (((STMethod) currentScope).compiledBlock != null)
            ((STMethod) currentScope).compiledBlock.bytecode = code.bytes();
        else
            ((STBlock) currentScope).compiledBlock = new STCompiledBlock(currentClassScope, (STBlock) currentScope);

        popScope();
        return code;
    }

    @Override
    public Code visitSmalltalkMethodBlock(SmalltalkParser.SmalltalkMethodBlockContext ctx) {

        return visit(ctx.body());
    }

    @Override
    public Code visitPrimitiveMethodBlock(SmalltalkParser.PrimitiveMethodBlockContext ctx) {
        return visit(ctx.SYMBOL());
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
        if (ctx.localVars() != null) {
            visit(ctx.localVars());
        }
        Code code = Code.None;

        for (SmalltalkParser.StatContext statContext : ctx.stat()) {
            code = code.join(visit(statContext));
            ((STBlock) currentScope).compiledBlock = new STCompiledBlock(currentClassScope, (STBlock) currentScope);
        }

        return code;
    }

    @Override
    public Code visitBlock(SmalltalkParser.BlockContext ctx) {
        Code code = Code.None;
        code = code.join(visit(ctx.body()));
        ((STBlock) currentScope).compiledBlock = new STCompiledBlock(currentClassScope, (STBlock) currentScope);
        return code;
    }

    @Override
    public Code visitAssign(SmalltalkParser.AssignContext ctx) {
        return aggregateResult(
                visit(ctx.messageExpression()),
                visit(ctx.lvalue())
        );
    }

    @Override
    public Code visitLvalue(SmalltalkParser.LvalueContext ctx) {
        VariableSymbol variableSymbol = (VariableSymbol) currentScope.resolve(ctx.ID().getText());
        if (variableSymbol instanceof STField) {
            Code code = Code.of(Bytecode.STORE_FIELD, (short) 0, (short) 0);
            code = aggregateResult(code, Code.of(Bytecode.POP));
            return code;
        } else {
            Code code = Code.of(Bytecode.STORE_LOCAL, (short) 0,
                    (short) 0,
                    (short) 0,
                    (short) currentScope.getSymbol(ctx.getText()).getInsertionOrderNumber());
            code = aggregateResult(code, Code.of(Bytecode.POP));
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
    public Code visitBinaryExpression(SmalltalkParser.BinaryExpressionContext ctx) {
        Code code = Code.None;
        for (SmalltalkParser.UnaryExpressionContext unaryExpressionContext : ctx.unaryExpression()) {
            code = code.join(visit(unaryExpressionContext));
        }

        return code;
    }

    @Override
    public Code visitUnaryIsPrimary(SmalltalkParser.UnaryIsPrimaryContext ctx) {
        return visit(ctx.primary());
    }

    @Override
    public Code visitLocalVars(SmalltalkParser.LocalVarsContext ctx) {
        return super.visitLocalVars(ctx);
    }

    @Override
    public Code visitId(SmalltalkParser.IdContext ctx) {
        String id = ctx.ID().getText();
        switch (id) {
            case "Transcript":
                int index = currentClassScope.stringTable.add(id);
                return Code.of(Bytecode.PUSH_GLOBAL).join(toLiteral(index));
            default:
                return Code.of(
                        Bytecode.PUSH_LOCAL,
                        (short) 0,
                        (short) 0,
                        (short) 0,
                        (short) currentScope.getSymbol(id).getInsertionOrderNumber()
                );
        }
    }

    @Override
    public Code visitLiteral(SmalltalkParser.LiteralContext ctx) {
        String id = ctx.getText();
        if (id.contains("\'")) {
            id = id.replace("\'", "");
        }
        if (ctx.NUMBER() == null) {
            int index = currentClassScope.stringTable.add(id);
            return Code.of(Bytecode.PUSH_LITERAL).join(toLiteral(index));
        } else {
            int index = currentClassScope.stringTable.size();
            return Code.of(Bytecode.PUSH_INT).join(intToBytes(index));
        }
    }

    @Override
    public Code visitKeywordMethod(SmalltalkParser.KeywordMethodContext ctx) {
        return super.visitKeywordMethod(ctx);
    }

    @Override
    public Code visitKeywordSend(SmalltalkParser.KeywordSendContext ctx) {
        Code code = visit(ctx.recv);
        for (SmalltalkParser.BinaryExpressionContext binaryExpressionContext : ctx.args) {
            code = code.join(visit(binaryExpressionContext));
        }
        code = sendKeywordMsg(ctx.recv, code, ctx.args, ctx.KEYWORD());
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
        Code e = Code.of(Bytecode.SEND).join(shortToBytes(args.size()).join(toLiteral(currentClassScope.stringTable.add(keywords.get(0).getText()))));
        Code codepop = aggregateResult(e, Code.of(Bytecode.POP));
        Code codenew = aggregateResult(code, codepop);
        return codenew;
    }

    public String getProgramSourceForSubtree(ParserRuleContext ctx) {
        return null;
    }
}
