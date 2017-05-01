package smalltalk.compiler;

import org.antlr.symtab.Scope;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.TerminalNode;
import org.stringtemplate.v4.compiler.STParser;
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
    public Code visitFile(SmalltalkParser.FileContext ctx) {
        currentScope = compiler.symtab.GLOBALS;
        Code code = visitChildren(ctx);
        return code;
    }

    @Override
    public Code visitClassDef(SmalltalkParser.ClassDefContext ctx) {
        currentClassScope = ctx.scope;
        pushScope(ctx.scope);

        visit(ctx.instanceVars());
        Code code = Code.None;
        for (SmalltalkParser.ClassMethodContext classMethodContext : ctx.classMethod()) {
            code = aggregateResult(code, visit(classMethodContext));
        }
        for (SmalltalkParser.MethodContext methodContext : ctx.method()) {
            code = aggregateResult(code, visit(methodContext));
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

        ((STMethod) currentScope).compiledBlock = new STCompiledBlock(currentClassScope, (STBlock) currentScope);
        ((STMethod) currentScope).compiledBlock.bytecode = code.bytes();

        popScope();
        return code;
    }

    @Override
    public Code visitSmalltalkMethodBlock(SmalltalkParser.SmalltalkMethodBlockContext ctx) {
        System.out.println(ctx.selector);
        System.out.println(ctx.args);
//        compiler.defineLocals(currentScope,ctx.args);
//        compiler.defineLocals(currentScope,);
        return visit(ctx.body());
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
        visit(ctx.localVars());
        Code code = Code.None;
        for (SmalltalkParser.StatContext statContext : ctx.stat()) {
            code = aggregateResult(code, visit(statContext));
        }
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
        Code code = visit(ctx.ID());
        return code;
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
            code = aggregateResult(code, visit(unaryExpressionContext));
        }

        return code;
    }

    @Override
    public Code visitUnaryIsPrimary(SmalltalkParser.UnaryIsPrimaryContext ctx) {
        return visit(ctx.primary());
    }

    @Override
    public Code visitId(SmalltalkParser.IdContext ctx) {
        return Code.of(
                Bytecode.PUSH_LOCAL,
                (short) 0,
                (short) 0,
                (short) 0,
                (short) currentScope.getSymbol(ctx.ID().getText()).getInsertionOrderNumber()
        );
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
        return null;
    }

    public String getProgramSourceForSubtree(ParserRuleContext ctx) {
        return null;
    }
}
