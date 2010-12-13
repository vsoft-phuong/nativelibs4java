/*
	Copyright (c) 2009 Olivier Chafik, All Rights Reserved
	
	This file is part of JNAerator (http://jnaerator.googlecode.com/).
	
	JNAerator is free software: you can redistribute it and/or modify
	it under the terms of the GNU Lesser General Public License as published by
	the Free Software Foundation, either version 3 of the License, or
	(at your option) any later version.
	
	JNAerator is distributed in the hope that it will be useful,
	but WITHOUT ANY WARRANTY; without even the implied warranty of
	MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
	GNU Lesser General Public License for more details.
	
	You should have received a copy of the GNU Lesser General Public License
	along with JNAerator.  If not, see <http://www.gnu.org/licenses/>.
*/
package com.ochafik.lang.jnaerator;

import java.io.PrintStream;
import com.ochafik.lang.jnaerator.parser.Identifier.SimpleIdentifier;
import com.ochafik.lang.jnaerator.parser.Statement.ExpressionStatement;
import org.bridj.FlagSet;
import org.bridj.BridJ;
import org.bridj.IntValuedEnum;
import org.bridj.StructObject;
import org.bridj.ValuedEnum;
import org.bridj.cpp.CPPObject;

import static com.ochafik.lang.SyntaxUtils.as;
//import org.bridj.structs.StructIO;
//import org.bridj.structs.Array;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.HashSet;
import java.util.Arrays;
import java.util.List;
import java.util.ArrayList;
import java.util.regex.Pattern;

import com.ochafik.lang.jnaerator.JNAeratorConfig.GenFeatures;
import com.ochafik.lang.jnaerator.TypeConversion.NL4JConversion;
import com.ochafik.lang.jnaerator.cplusplus.CPlusPlusMangler;
import com.ochafik.lang.jnaerator.parser.*;
import static com.ochafik.lang.jnaerator.parser.Statement.*;
import com.ochafik.lang.jnaerator.parser.StoredDeclarations.*;
import com.ochafik.lang.jnaerator.parser.Struct.MemberVisibility;
import com.ochafik.lang.jnaerator.parser.TypeRef.*;
import com.ochafik.lang.jnaerator.parser.Expression.*;
import com.ochafik.lang.jnaerator.parser.Function.Type;
import com.ochafik.lang.jnaerator.parser.DeclarationsHolder.ListWrapper;
import com.ochafik.lang.jnaerator.parser.Declarator.*;
import com.ochafik.lang.jnaerator.runtime.VirtualTablePointer;
import com.ochafik.util.CompoundCollection;
import com.ochafik.util.listenable.Pair;
import com.ochafik.util.string.StringUtils;

import java.net.URL;
import java.net.URLConnection;
import java.text.MessageFormat;
import static com.ochafik.lang.jnaerator.parser.ElementsHelper.*;
import static com.ochafik.lang.jnaerator.TypeConversion.*;

/*
mvn -o compile exec:java -Dexec.mainClass=com.ochafik.lang.jnaerator.JNAerator

*/
public class BridJer {
    Result result;
    public BridJer(Result result) {
        this.result = result;
    }
    
    Expression staticPtrMethod(String name, Expression... args) {
        return methodCall(expr(typeRef(ptrClass())), name, args);
    }
    int iFile = 0;
	public Pair<Element, List<Declaration>> convertToJava(Element element) {
        //element = element.clone();
        try {
            PrintStream out = new PrintStream("jnaerator-" + (iFile++) + ".out");
            out.println(element);
            out.close();
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        final List<Declaration> extraDeclarationsOut = new ArrayList<Declaration>();
        
        final Set<Pair<Element, Integer>> referencedElements = new HashSet<Pair<Element, Integer>>();
        element.accept(new Scanner() {
            @Override
            public void visitUnaryOp(UnaryOp unaryOp) {
                super.visitUnaryOp(unaryOp);
                
                if (unaryOp.getOperator() == UnaryOperator.Reference) {
                		if (unaryOp.getOperand() instanceof VariableRef) {
                			VariableRef vr = (VariableRef)unaryOp.getOperand();
						Element e = result.symbols.getVariable(vr.getName());
						if (e != null)
							referencedElements.add(new Pair<Element, Integer>(e, e.getId()));
					}	
                }
            }
        });
        
        final Map<Integer, String> referencedElementsChangedNames = new HashMap<Integer, String>();
        for (Pair<Element, Integer> kv : referencedElements) {
            Element e = kv.getKey();
            //int id = kv
            if (e instanceof DirectDeclarator) {
                DirectDeclarator decl = (DirectDeclarator)e;
				String name = decl.getName();
				String changedName = "p" + StringUtils.capitalize(name);
                referencedElementsChangedNames.put(e.getId(), changedName);
				decl.setName(changedName);
				VariablesDeclaration vd = (VariablesDeclaration)decl.getParentElement();
				TypeRef tr = vd.getValueType();
				TypeRef wrapper = getWrapperType(tr);
				vd.setValueType(pointerToTypeRef(wrapper));
                Expression defVal = decl.getDefaultValue();
				if (defVal != null) {
					decl.setDefaultValue(staticPtrMethod("pointerTo" + StringUtils.capitalize(tr.toString()), defVal));
				}
			}
        }
        
        element.accept(new Scanner() {
            @Override
            public void visitIdentifier(Identifier identifier) {
                super.visitIdentifier(identifier);
                Element e = result.symbols.getVariable(identifier);
                if (e != null && isReferenced(e)) {
					String changedName = referencedElementsChangedNames.get(e.getId());
					if (changedName != null) {
						Identifier replacedIdentifier = ident(changedName);
						identifier.replaceBy(replacedIdentifier);
						referencedElements.add(new Pair<Element, Integer>(replacedIdentifier, replacedIdentifier.getId()));
					}
				}
            }
            
            int nConstants = 0;
            
            @Override
            public void visitConstant(Constant c) {
                if (c.getValue() instanceof String) {
                    //Struct s = c.findParentOfType(Struct.class);
                    Class charClass;
                    String ptrMethodName;
                    if (c.getType() == Constant.Type.LongString) {
                        charClass = Short.class;
                        ptrMethodName = "pointerToWideCString";
                    } else {
                        charClass = Byte.class;
                        ptrMethodName = "pointerToCString";
                    }
                    String fieldName = "strConstant" + (++nConstants);
                    VariablesDeclaration staticConstField = new VariablesDeclaration(typeRef(ident(ptrClass(), expr(typeRef(charClass)))), new DirectDeclarator(fieldName, staticPtrMethod(ptrMethodName, c.clone())));
                    staticConstField.addModifiers(Modifier.Static, Modifier.Private, Modifier.Final);
                    //s.addDeclaration(staticConstField);
                    extraDeclarationsOut.add(staticConstField);
                    c.replaceBy(varRef(fieldName));
                    return;
                }
                
                super.visitConstant(c);
            }
            boolean isReferenced(Element e) {
            		if (e == null)
            			return false;
                return referencedElements.contains(new Pair<Element, Integer>(e, e.getId()));
            }
            @Override
            public void visitVariableRef(VariableRef vr) {
                super.visitVariableRef(vr);
                Identifier ident = vr.getName();
                if (isReferenced(ident)) {
                    vr.replaceBy(methodCall(varRef(ident), "get"));
				} else {
                    String identStr = ident.toString();
                    if ("NULL".equals(identStr))
                        vr.replaceBy(nullExpr());
                    
                }
            }
            
            void replaceMalloc(TypeRef pointedType, Element toReplace, Expression sizeExpression) {
                // TODO handle casts and sizeof expressions !
                toReplace.replaceBy(staticPtrMethod("allocateBytes", sizeExpression));
            }
            
            @Override
            public void visitFunctionCall(FunctionCall fc) {
                super.visitFunctionCall(fc);
                if (fc.getTarget() == null && (fc.getFunction() instanceof VariableRef)) {
                    Identifier ident = ((VariableRef)fc.getFunction()).getName();
                    if ((ident instanceof SimpleIdentifier) && ((SimpleIdentifier)ident).getTemplateArguments().isEmpty()) {
                        String name = ident.toString();
                        List<Pair<String, Expression>> arguments = fc.getArguments();
                        int nArgs = arguments.size();
                        
                        Expression 
                            arg1 = nArgs > 0 ? arguments.get(0).getValue() : null, 
                            arg2 = nArgs > 1 ? arguments.get(1).getValue() : null, 
                            arg3 = nArgs > 2 ? arguments.get(2).getValue() : null;
                                
                        switch (nArgs) {
                            case 1:
                                if ("malloc".equals(name)) {
                                    replaceMalloc(null, fc, arg1);
                                    return;
                                } else if ("free".equals(name)) {
                                    fc.replaceBy(methodCall(arg1, "release"));
                                    return;
                                }
                                break;
                            case 3:
                                if ("memset".equals(name)) {
                                    Expression value = arg2, num = arg3;
                                    if ("0".equals(value + ""))
                                        fc.replaceBy(methodCall(arg1, "clearBytes", expr(0), num));
                                    else
                                        fc.replaceBy(methodCall(arg1, "clearBytes", expr(0), num, value));
                                    return;
                                } else if ("memcpy".equals(name)) {
                                    Expression dest = arg1, source = arg2, num = arg3;
                                    fc.replaceBy(methodCall(source, "copyBytesTo", expr(0), dest, expr(0), num));
                                    return;
                                } else if ("memmov".equals(name)) {
                                    Expression dest = arg1, source = arg2, num = arg3;
                                    fc.replaceBy(methodCall(source, "moveBytesTo", expr(0), dest, expr(0), num));
                                    return;
                                } else if ("memcmp".equals(name)) {
                                    Expression ptr1 = arg1, ptr2 = arg2, num = arg3;
                                    fc.replaceBy(methodCall(ptr1, "compareBytes", ptr2, num));
                                    return;
                                }
                                break;
                        }
                        
                        if ("printf".equals(name)) {
                            fc.replaceBy(methodCall(memberRef(expr(typeRef(System.class)), "out"), "println", formatStr(arg1, arguments, 1)));
                            return;
                        } else if ("sprintf".equals(name)) {
                            fc.replaceBy(methodCall(arg1, "setCString", formatStr(arg2, arguments, 2)));
                            return;
                        }
                    }
                }
            }
            
            
            Expression formatStr(Expression str, List<Pair<String, Expression>> arguments, int argsToSkip) {
                List<Expression> fmtArgs = new ArrayList<Expression>();
                for (int i = argsToSkip, nArgs = arguments.size(); i < nArgs; i++) 
                    fmtArgs.add(arguments.get(i).getValue());

                if (fmtArgs.isEmpty())
                    return str;
                else
                    return methodCall(str, "format", fmtArgs.toArray(new Expression[fmtArgs.size()]));
            }
            @Override
            public void visitIf(If ifStat) {
                super.visitIf(ifStat);
                Expression cond = ifStat.getCondition();
                if (!(cond instanceof BinaryOp)) // TODO use typing rather than this hack to detect implicit boolean conversion in if statements (and not only there)
                    cond.replaceBy(expr(cond, BinaryOperator.IsDifferent, expr(0)));
            }
            @Override
            public void visitUnaryOp(UnaryOp unaryOp) {
                if (unaryOp.getOperator() == UnaryOperator.Reference) {
                    if (unaryOp.getOperand() instanceof VariableRef) {
                        VariableRef vr = (VariableRef)unaryOp.getOperand();

                        Identifier ident = vr.getName();
                        Element e = result.symbols.getVariable(ident);
						if ((e != null || isReferenced(e)) || isReferenced(ident)) {
                            String changedName = referencedElementsChangedNames.get(e.getId());
                            if (changedName != null) {
                                Element rep = varRef(changedName);
                                unaryOp.replaceBy(rep);
                                visit(rep);
                                return;
                            }
						}
					}
                }
                super.visitUnaryOp(unaryOp);
            }
        });
        
        element.accept(new Scanner() {
            
            @Override
            public void visitUnaryOp(UnaryOp unaryOp) {
                super.visitUnaryOp(unaryOp);
                
                switch (unaryOp.getOperator()) {
                    case Dereference:
                        unaryOp.replaceBy(methodCall(unaryOp.getOperand(), "get"));
                        break;
                    case Reference:
                        // TODO handle special case of referenced primitives, for instance
                        unaryOp.replaceBy(methodCall(unaryOp.getOperand(), "getReference"));
                        break;
                }
            }

            @Override
            public void visitCast(Cast cast) {
                super.visitCast(cast);
                if (cast.getType() instanceof TargettedTypeRef) {
                    cast.replaceBy(methodCall(cast.getTarget(), "asPointerTo", result.typeConverter.typeLiteral(cast.getType())));
                }
            }

            @Override
            public void visitArrayAccess(ArrayAccess arrayAccess) {
                super.visitArrayAccess(arrayAccess);
                arrayAccess.replaceBy(methodCall(arrayAccess.getTarget(), "get", arrayAccess.getIndex()));
            }

            @Override
            public void visitMemberRef(MemberRef memberRef) {
                // TODO restrict to struct/class fields
                if (!(memberRef.getParentElement() instanceof FunctionCall))
					if (memberRef.getName() != null) {
                        Expression rep = methodCall(memberRef.getTarget(), memberRef.getName().toString());
						memberRef.replaceBy(rep);
                        rep.accept(this);
                        return;
                    }
                
                super.visitMemberRef(memberRef);
            }
            
            @Override
            public void visitVariablesDeclaration(VariablesDeclaration v) {
                super.visitVariablesDeclaration(v);
                if (v.getDeclarators().size() == 1) {
                    Declarator decl = v.getDeclarators().get(0);
                    //DirectDeclarator decl = (DirectDeclarator);
                    MutableByDeclarator mutatedType = decl.mutateType(v.getValueType());
                    if (mutatedType instanceof TypeRef) {
                        TypeRef t = (TypeRef)mutatedType;
                        if (decl.getDefaultValue() == null && result.symbols.isClassType(t)) {
                            decl.setDefaultValue(new Expression.New(t.clone()));
                            Expression vr = varRef(new SimpleIdentifier(decl.resolveName()));
                            ((Statement.Block)v.getParentElement().getParentElement()).addStatement(stat(methodCall(expr(typeRef(BridJ.class)), "delete", vr)));
                        }
                    }
                }
            }

            @Override
            public void visitAssignmentOp(AssignmentOp assignment) {
                BinaryOperator binOp = assignment.getOperator().getCorrespondingBinaryOp();
                Expression value = assignment.getValue();
                value.setParenthesisIfNeeded();
                if (assignment.getTarget() instanceof UnaryOp) {
                    UnaryOp uop = (UnaryOp)assignment.getTarget();
                    if (uop.getOperator() == UnaryOperator.Dereference) {
                        visit(uop.getOperand());
                        visit(assignment.getValue());
                        Expression target = uop.getOperand();
                        if (binOp != null) {
                            value = expr(methodCall(target.clone(), "get"), binOp, value);
                        }
                        assignment.replaceBy(methodCall(target, "set", value));
                        return;
                    }
                }
                if (assignment.getTarget() instanceof ArrayAccess) {
                    ArrayAccess aa = (ArrayAccess)assignment.getTarget();
                    visit(aa.getTarget());
                    visit(aa.getIndex());
                    visit(assignment.getValue());
                    Expression target = aa.getTarget();
                    Expression index = aa.getIndex();
                    if (binOp != null) {
                        value = expr(methodCall(target.clone(), "get", index.clone()), binOp, value);
                    }
                    assignment.replaceBy(methodCall(target, "set", index, value));
                    return;
                }
                if (assignment.getTarget() instanceof MemberRef) {
                    MemberRef mr = (MemberRef)assignment.getTarget();
                    Expression target = mr.getTarget();
                    String name = mr.getName().toString();
                    if (binOp != null) {
                        value = expr(methodCall(target.clone(), name), binOp, value);
                    }
                    assignment.replaceBy(methodCall(target, name, value));
                    return;
                }
                super.visitAssignmentOp(assignment);
                
            }
            
            
            @Override
            public void visitNew(New new1) {
                super.visitNew(new1);
                if (new1.getConstruction() == null)
                    new1.replaceBy(staticPtrMethod("allocate" + StringUtils.capitalize(new1.getType().toString())));
            }

            public void notSup(Element x, String msg) throws UnsupportedConversionException {
                throw new UnsupportedConversionException(x, msg);
            }

            /*
            @Override
            public void visitExpressionStatement(ExpressionStatement expressionStatement) {
                super.visitExpressionStatement(expressionStatement);
                if (expressionStatement.getExpression() instanceof UnaryOp) {
                    UnaryOp uop = (UnaryOp)expressionStatement.getExpression();
                    Expression target = uop.getOperand();
                    switch (uop.getOperator()) {
                        case PostIncr:
                        case PreIncr:
                            expressionStatement.replaceBy(expr(target));
                    }
                }
            }*/
            
            @Override
            public void visitNewArray(NewArray newArray) {
                super.visitNewArray(newArray);
                if (newArray.getType() instanceof Primitive) {
                    if (newArray.getDimensions().size() != 1)
                        notSup(newArray, "TODO only dimensions 1 to 3 are supported for primitive array creations !");
                
                    newArray.replaceBy(
                        staticPtrMethod(
                            "allocate" + StringUtils.capitalize(newArray.getType().toString()) + "s", 
                            newArray.getDimensions().toArray(new Expression[newArray.getDimensions().size()])
                        )
                    );
                } else {
                    if (newArray.getDimensions().size() != 1)
                        notSup(newArray, "TODO only dimension 1 is supported for reference array creations !");
                
                    newArray.replaceBy(
                        staticPtrMethod(
                            "allocateArray",  
                            result.typeConverter.typeLiteral(newArray.getType()), 
                            newArray.getDimensions().get(0)
                        )
                    );
                }
            }

            @Override
            public void visitPointer(Pointer pointer) {
                super.visitPointer(pointer);
            }
            
            @Override
            protected void visitTargettedTypeRef(TargettedTypeRef targettedTypeRef) {
                super.visitTargettedTypeRef(targettedTypeRef);
                if (targettedTypeRef.getTarget() != null)
                    targettedTypeRef.replaceBy(pointerToTypeRef(targettedTypeRef.getTarget().clone()));
            }
        });
        return new Pair<Element, List<Declaration>>(element, extraDeclarationsOut);
    }
    TypeRef getWrapperType(TypeRef tr) {
    		JavaPrim prim = result.typeConverter.getPrimitive(tr, null);
    		if (prim != null)
    			return typeRef(prim.wrapperType);
    		return tr;
    }
	TypeRef pointerToTypeRef(TypeRef targetType) {
		Identifier id = ident(ptrClass());
		id.resolveLastSimpleIdentifier().addTemplateArgument(expr(targetType));
		return typeRef(id);
	}
    Class ptrClass() {
        return result.config.runtime.pointerClass;
    }
}